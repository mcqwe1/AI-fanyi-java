package com.aifanyi.service;

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
 * 转写与时间轴修正已抽到 {@link MediaTranscribeService}（与模式无关，Agent 流水线共用）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskPipeline {

    private final TranslationTaskMapper taskMapper;
    private final SubtitleMapper subtitleMapper;
    private final GlossaryTermMapper glossaryMapper;
    private final StorageService storage;
    private final MediaTranscribeService transcriber;
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
            // 1. 抽音频 + 2. ASR 转写 + 反幻觉 + 时间轴对齐（与模式无关，见 MediaTranscribeService）
            update(task, TaskStatus.EXTRACTING_AUDIO, 10);
            final TranslationTask progressTask = task;
            update(task, TaskStatus.TRANSCRIBING, 30);
            // 转写是最长的一段（本地 CPU 跑大模型可达视频时长量级），把 0~1 进度映射到 30~55%，
            // 免得进度条长时间停在 30% 被用户当成卡死
            java.util.function.DoubleConsumer asrProgress = ratio -> {
                int pct = 30 + (int) Math.round(ratio * 25);
                Integer cur = progressTask.getProgress();
                if (cur == null || pct > cur) {
                    progressTask.setProgress(pct);
                    taskMapper.updateById(progressTask);
                }
            };
            List<Segment> segments = transcriber.transcribe(task,
                    () -> taskMapper.updateById(progressTask), asrProgress);

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
                    String customPrompt = settings.effectiveTermPrompt(task.getUserId());
                    List<GeminiClient.TermCandidate> cands = gemini.extractTerms(
                            transcript, task.getSourceLang(), task.getTargetLang(), gcfg, customPrompt);
                    upsertAutoTerms(task.getProjectId(), cands);
                } catch (Exception e) {
                    // 抽术语失败不阻断翻译，仅记录
                    log.warn("KB 抽术语失败（继续用已有术语表翻译）: {}", e.getMessage());
                }
                glossary = loadGlossary(task.getProjectId());
            }

            // 3. LLM 翻译（KB 模式与抽术语同用 Gemini，保证术语表遵循一致；普通模式用 LLM 设置）
            update(task, TaskStatus.TRANSLATING, 60);
            LlmConfig llmCfg = settings.effectiveTranslationLlm(task.getUserId(), kb);
            log.info("翻译使用模型: {} @ {}", llmCfg.model(), llmCfg.baseUrl());
            List<String> targets = translator.translate(sources, task.getTargetLang(), llmCfg,
                    glossary, task.getStylePrompt());

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
