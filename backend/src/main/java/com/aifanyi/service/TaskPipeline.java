package com.aifanyi.service;

import com.aifanyi.asr.Segment;
import com.aifanyi.domain.TaskStatus;
import com.aifanyi.entity.GlossaryTerm;
import com.aifanyi.entity.Subtitle;
import com.aifanyi.entity.TranslationTask;
import com.aifanyi.llm.LlmConfig;
import com.aifanyi.llm.LlmTranslator;
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
    private final KbService kbService;
    private final StorageService storage;
    private final MediaTranscribeService transcriber;
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
            // 1. 抽音频 + 2. ASR 转写 + 反幻觉 + 时间轴对齐（与模式无关，见 MediaTranscribeService）
            // 用户自带字幕时这一整段会被跳过，状态也别再显示「正在识别语音」误导人
            boolean supplied = task.getSubtitleSourcePath() != null
                    && !task.getSubtitleSourcePath().isBlank();
            final TranslationTask progressTask = task;
            if (supplied) {
                update(task, TaskStatus.TRANSCRIBING, 50);
            } else {
                update(task, TaskStatus.EXTRACTING_AUDIO, 10);
                update(task, TaskStatus.TRANSCRIBING, 30);
            }
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

            // 术语注入 = 用户勾选的术语库，统一走 KbService
            Map<String, String> glossary = kbService.loadForInjection(
                    task.getUserId(), task.getGlossaryProjectIds(), null);

            // 3. LLM 翻译
            update(task, TaskStatus.TRANSLATING, 60);
            LlmConfig llmCfg = settings.effectiveLlm(task.getUserId());
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
