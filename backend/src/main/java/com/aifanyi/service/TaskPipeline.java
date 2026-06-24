package com.aifanyi.service;

import com.aifanyi.asr.AsrContext;
import com.aifanyi.asr.AsrProvider;
import com.aifanyi.asr.AsrProviderFactory;
import com.aifanyi.asr.Segment;
import com.aifanyi.domain.TaskStatus;
import com.aifanyi.entity.Subtitle;
import com.aifanyi.entity.TranslationTask;
import com.aifanyi.llm.LlmConfig;
import com.aifanyi.llm.LlmTranslator;
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
import java.util.List;

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
    private final StorageService storage;
    private final FfmpegService ffmpeg;
    private final AsrProviderFactory asrFactory;
    private final LlmTranslator translator;
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
            List<Segment> segments = asr.transcribe(audio, task.getSourceLang(),
                    new AsrContext(route.apiKey(), route.model()));
            if (segments.isEmpty()) {
                throw new com.aifanyi.common.BizException("未识别到语音内容");
            }
            // 修正时间轴：去空白、去重叠（避免下一句字幕提前出现）、兜底最短时长
            segments = com.aifanyi.media.SubtitleTimingFixer.fix(segments);

            // 3. LLM 翻译（按用户设置解析 baseUrl/key/model）
            update(task, TaskStatus.TRANSLATING, 60);
            List<String> sources = segments.stream().map(Segment::text).toList();
            LlmConfig llmCfg = settings.effectiveLlm(task.getUserId());
            List<String> targets = translator.translate(sources, task.getTargetLang(), llmCfg);

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
