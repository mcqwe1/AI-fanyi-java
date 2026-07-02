package com.aifanyi.service;

import com.aifanyi.asr.AsrContext;
import com.aifanyi.asr.AsrProvider;
import com.aifanyi.asr.AsrProviderFactory;
import com.aifanyi.asr.Segment;
import com.aifanyi.domain.TaskMode;
import com.aifanyi.domain.TaskStatus;
import com.aifanyi.entity.GlossaryTerm;
import com.aifanyi.entity.Subtitle;
import com.aifanyi.entity.TranslationTask;
import com.aifanyi.llm.GeminiClient;
import com.aifanyi.llm.GeminiConfig;
import com.aifanyi.llm.LlmConfig;
import com.aifanyi.llm.LlmTranslator;
import com.aifanyi.mapper.GlossaryTermMapper;
import com.aifanyi.mapper.SubtitleMapper;
import com.aifanyi.mapper.TranslationTaskMapper;
import com.aifanyi.media.FfmpegService;
import com.aifanyi.media.SrtService;
import com.aifanyi.storage.StorageService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 普通模式翻译流水线（异步）。
 * 抽音频 → ASR 转写 → LLM 翻译 → 落库字幕 → 生成 SRT。
 * 知识库模式（阶段3）将在此扩展 ANALYZING_VIDEO / BUILDING_KB 步骤。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskPipeline {

    private final TranslationTaskMapper taskMapper;
    private final SubtitleMapper subtitleMapper;
    private final GlossaryTermMapper glossaryMapper;
    private final StorageService storage;
    private final FfmpegService ffmpeg;
    private final AsrProviderFactory asrFactory;
    private final LlmTranslator translator;
    private final GeminiClient gemini;
    private final SettingsService settings;

    @Async("taskExecutor")
    public void runAsync(Long taskId) {
        TranslationTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.error("任务不存在: {}", taskId);
            return;
        }
        try {
            // 1. 抽音频
            update(task, TaskStatus.EXTRACTING_AUDIO, 10);
            Path video = Path.of(task.getVideoPath());
            Path audio = storage.resolve(taskId, "audio.mp3");
            ffmpeg.extractAudio(video, audio);
            task.setAudioPath(audio.toString());
            taskMapper.updateById(task);

            // 2. ASR 转写（按用户设置解析 provider/model/key）
            update(task, TaskStatus.TRANSCRIBING, 30);
            AsrRoute route = resolveAsr(task.getAsrProvider(), task.getUserId());
            AsrProvider asr = asrFactory.get(route.provider());
            List<Segment> segments = asr.transcribe(audio, normLang(task.getSourceLang()),
                    new AsrContext(route.apiKey(), route.model()));
            if (segments.isEmpty()) {
                throw new com.aifanyi.common.BizException("未识别到语音内容");
            }
            // 丢弃静音处的幻觉字幕（Whisper 常在静音段幻觉"感谢观看/you"等）
            java.util.List<long[]> silence = ffmpeg.detectSilenceMs(audio, -50, 0.8);
            segments = com.aifanyi.media.SubtitleTimingFixer.dropSilenceHallucinations(segments, silence);
            if (segments.isEmpty()) {
                throw new com.aifanyi.common.BizException("未识别到有效语音（疑似全静音）");
            }
            // 修正时间轴：去空白、去重叠（避免下一句字幕提前出现）、兜底最短时长
            segments = com.aifanyi.media.SubtitleTimingFixer.fix(segments);

            List<String> sources = segments.stream().map(Segment::text).toList();

            // KB 模式：Gemini 联网抽术语 → 入库（origin=auto，去重保留人工条目）
            boolean kb = TaskMode.KB.name().equalsIgnoreCase(task.getMode()) && task.getProjectId() != null;
            Map<String, String> glossary = new LinkedHashMap<>();
            if (kb) {
                update(task, TaskStatus.BUILDING_KB, 50);
                // 配置不完整 → 直接失败并指引设置页；抽术语本身失败 → 不阻断翻译
                GeminiConfig gcfg = settings.effectiveGemini(task.getUserId());
                try {
                    String transcript = String.join("\n", sources);
                    List<GeminiClient.TermCandidate> cands = gemini.extractTerms(
                            transcript, task.getSourceLang(), task.getTargetLang(), gcfg);
                    upsertAutoTerms(task.getProjectId(), cands);
                } catch (Exception e) {
                    // 抽术语失败不阻断翻译，仅记录
                    log.warn("KB 抽术语失败（继续用已有术语表翻译）: {}", e.getMessage());
                }
                glossary = loadGlossary(task.getProjectId());
            }

            // 3. LLM 翻译（按用户设置解析 baseUrl/key/model；KB 模式套术语表）
            update(task, TaskStatus.TRANSLATING, 60);
            LlmConfig llmCfg = settings.effectiveLlm(task.getUserId());
            List<String> targets = translator.translate(sources, task.getTargetLang(), llmCfg, glossary);

            // 4. 落库字幕
            subtitleMapper.delete(Wrappers.<Subtitle>lambdaQuery().eq(Subtitle::getTaskId, taskId));
            List<Subtitle> subs = new ArrayList<>(segments.size());
            for (int i = 0; i < segments.size(); i++) {
                Segment seg = segments.get(i);
                Subtitle sub = new Subtitle();
                sub.setTaskId(taskId);
                sub.setSeq(i + 1);
                sub.setStartMs(seg.startMs());
                sub.setEndMs(seg.endMs());
                sub.setSourceText(seg.text());
                sub.setTargetText(i < targets.size() ? targets.get(i) : seg.text());
                subtitleMapper.insert(sub);
                subs.add(sub);
            }

            // 5. 生成 SRT 文件
            String srt = task.getBilingual() != null && task.getBilingual() == 1
                    ? SrtService.toBilingualSrt(subs)
                    : SrtService.toSrt(subs);
            Path srtPath = storage.resolve(taskId, "subtitle.srt");
            Files.writeString(srtPath, srt, StandardCharsets.UTF_8);
            task.setSrtPath(srtPath.toString());
            taskMapper.updateById(task);

            // 阶段2：若 burnSubtitle，则用 FFmpeg 烧录；当前 MVP 仅产出 SRT
            update(task, TaskStatus.DONE, 100);
            log.info("任务完成: {} ({} 行字幕)", taskId, subs.size());
        } catch (Exception e) {
            log.error("任务失败: {}", taskId, e);
            task.setStatus(TaskStatus.FAILED.name());
            task.setErrorMsg(truncate(e.getMessage(), 900));
            taskMapper.updateById(task);
        }
    }

    /** 术语候选入库：同项目内已存在的 source_term 跳过（不覆盖人工/已审条目），其余作为 auto 新增。 */
    private void upsertAutoTerms(Long projectId, List<GeminiClient.TermCandidate> cands) {
        if (cands == null || cands.isEmpty()) return;
        List<GlossaryTerm> existing = glossaryMapper.selectList(
                Wrappers.<GlossaryTerm>lambdaQuery().eq(GlossaryTerm::getProjectId, projectId));
        java.util.Set<String> have = new java.util.HashSet<>();
        for (GlossaryTerm t : existing) have.add(t.getSourceTerm());
        int added = 0;
        for (GeminiClient.TermCandidate c : cands) {
            if (have.contains(c.source())) continue;
            GlossaryTerm t = new GlossaryTerm();
            t.setProjectId(projectId);
            t.setSourceTerm(c.source());
            t.setTargetTerm(c.target());
            t.setCategory(c.category());
            t.setNote(c.note());
            t.setOrigin("auto");
            t.setEnabled(1);
            glossaryMapper.insert(t);
            have.add(c.source());
            added++;
        }
        log.info("KB 项目 {} 新增 {} 条自动术语", projectId, added);
    }

    /** 载入项目启用中的术语表为 原文→译法 映射（长词优先，避免子串误匹配）。 */
    private Map<String, String> loadGlossary(Long projectId) {
        List<GlossaryTerm> terms = glossaryMapper.selectList(
                Wrappers.<GlossaryTerm>lambdaQuery()
                        .eq(GlossaryTerm::getProjectId, projectId)
                        .eq(GlossaryTerm::getEnabled, 1));
        terms.sort((a, b) -> Integer.compare(
                b.getSourceTerm() == null ? 0 : b.getSourceTerm().length(),
                a.getSourceTerm() == null ? 0 : a.getSourceTerm().length()));
        Map<String, String> map = new LinkedHashMap<>();
        for (GlossaryTerm t : terms) {
            if (t.getSourceTerm() != null && t.getTargetTerm() != null) {
                map.put(t.getSourceTerm(), t.getTargetTerm());
            }
        }
        return map;
    }

    /** 把人类语言标签规范化为 Whisper/Groq 的 ISO 代码；识别不了返回 "auto"（自动检测）。 */
    private static final Map<String, String> LANG_CODE = Map.ofEntries(
            Map.entry("日语", "ja"), Map.entry("日文", "ja"), Map.entry("japanese", "ja"),
            Map.entry("英语", "en"), Map.entry("英文", "en"), Map.entry("english", "en"),
            Map.entry("韩语", "ko"), Map.entry("韩文", "ko"), Map.entry("korean", "ko"),
            Map.entry("中文", "zh"), Map.entry("汉语", "zh"), Map.entry("chinese", "zh"),
            Map.entry("法语", "fr"), Map.entry("德语", "de"), Map.entry("西班牙语", "es"),
            Map.entry("俄语", "ru"), Map.entry("葡萄牙语", "pt"), Map.entry("意大利语", "it"),
            Map.entry("泰语", "th"), Map.entry("越南语", "vi"), Map.entry("印尼语", "id"),
            Map.entry("阿拉伯语", "ar"), Map.entry("印地语", "hi"), Map.entry("荷兰语", "nl"));

    private String normLang(String v) {
        if (v == null || v.isBlank()) return "auto";
        String k = v.trim();
        if ("auto".equalsIgnoreCase(k)) return "auto";
        String mapped = LANG_CODE.get(k);
        if (mapped != null) return mapped;
        mapped = LANG_CODE.get(k.toLowerCase());
        if (mapped != null) return mapped;
        // 已是 2 字母代码就直接用，否则交给自动检测
        return (k.length() == 2 && k.chars().allMatch(Character::isLetter)) ? k.toLowerCase() : "auto";
    }

    /** 把前端 ASR 选择值解析为 (provider, model, apiKey)。 */
    private AsrRoute resolveAsr(String selector, Long userId) {
        String sel = selector == null ? "groq" : selector.trim().toLowerCase();
        switch (sel) {
            case "groq":
                return new AsrRoute("groq", null, settings.effectiveGroqKey(userId));
            case "groq-turbo":
                return new AsrRoute("groq", "whisper-large-v3-turbo", settings.effectiveGroqKey(userId));
            case "qwen":
                return new AsrRoute("qwen", null, settings.effectiveDashscopeKey(userId));
            case "glm":
                return new AsrRoute("glm", null, settings.effectiveZhipuKey(userId));
            default:
                if (sel.startsWith("local")) {
                    String size = sel.contains("-") ? sel.substring(sel.indexOf('-') + 1) : "large-v3";
                    return new AsrRoute("local", size, null);
                }
                return new AsrRoute("groq", null, settings.effectiveGroqKey(userId));
        }
    }

    private record AsrRoute(String provider, String model, String apiKey) {
    }

    private void update(TranslationTask task, TaskStatus status, int progress) {
        task.setStatus(status.name());
        task.setProgress(progress);
        taskMapper.updateById(task);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
