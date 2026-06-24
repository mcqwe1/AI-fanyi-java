package com.aifanyi.service;

import com.aifanyi.common.BizException;
import com.aifanyi.domain.SubtitleStyle;
import com.aifanyi.domain.TaskStatus;
import com.aifanyi.entity.Subtitle;
import com.aifanyi.entity.TranslationTask;
import com.aifanyi.mapper.SubtitleMapper;
import com.aifanyi.mapper.TranslationTaskMapper;
import com.aifanyi.media.AssSubtitleWriter;
import com.aifanyi.media.FfmpegService;
import com.aifanyi.storage.StorageService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 字幕烧录与样式预览。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BurnService {

    private final TranslationTaskMapper taskMapper;
    private final SubtitleMapper subtitleMapper;
    private final StorageService storage;
    private final FfmpegService ffmpeg;

    /** 生成样式预览图（抽一帧 + 样例字幕），同步返回 jpg 路径。 */
    public Path previewImage(TranslationTask task, SubtitleStyle style) {
        Path video = Path.of(task.getVideoPath());
        int[] wh = ffmpeg.probeResolution(video);

        String sample = firstSubtitleText(task.getId());
        String ass = AssSubtitleWriter.buildSample(style, wh[0], wh[1], sample);
        Path assFile = storage.resolve(task.getId(), "preview.ass");
        writeFile(assFile, ass);

        Path jpg = storage.resolve(task.getId(), "preview.jpg");
        ffmpeg.previewFrame(video, assFile, jpg, "00:00:02");
        return jpg;
    }

    /** 异步把字幕烧录进视频。 */
    @Async("taskExecutor")
    public void burnAsync(Long taskId, SubtitleStyle style) {
        TranslationTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.error("烧录失败，任务不存在: {}", taskId);
            return;
        }
        try {
            task.setStatus(TaskStatus.BURNING.name());
            task.setErrorMsg(null);
            taskMapper.updateById(task);

            List<Subtitle> subs = subtitleMapper.selectList(
                    Wrappers.<Subtitle>lambdaQuery()
                            .eq(Subtitle::getTaskId, taskId)
                            .orderByAsc(Subtitle::getSeq));
            if (subs.isEmpty()) {
                throw new BizException("没有字幕可烧录");
            }

            Path video = Path.of(task.getVideoPath());
            int[] wh = ffmpeg.probeResolution(video);
            double totalSec = ffmpeg.probeDurationSec(video);
            boolean bilingual = task.getBilingual() != null && task.getBilingual() == 1;
            String ass = AssSubtitleWriter.build(subs, style, wh[0], wh[1], bilingual);
            Path assFile = storage.resolve(taskId, "subtitle.ass");
            writeFile(assFile, ass);

            task.setProgress(0);
            taskMapper.updateById(task);

            Path out = storage.resolve(taskId, "burned.mp4");
            ffmpeg.burnAss(video, assFile, out, totalSec, pct -> {
                task.setProgress(pct);
                taskMapper.updateById(task);
            });

            task.setOutputVideoPath(out.toString());
            task.setStatus(TaskStatus.DONE.name());
            task.setProgress(100);
            taskMapper.updateById(task);
            log.info("烧录完成: {} -> {}", taskId, out);
        } catch (Exception e) {
            log.error("烧录失败: {}", taskId, e);
            task.setStatus(TaskStatus.FAILED.name());
            task.setErrorMsg(truncate("烧录失败: " + e.getMessage(), 900));
            taskMapper.updateById(task);
        }
    }

    private String firstSubtitleText(Long taskId) {
        // 取前若干条，挑一条足够长的作样例（避免预览只显示单字）
        List<Subtitle> subs = subtitleMapper.selectList(
                Wrappers.<Subtitle>lambdaQuery()
                        .eq(Subtitle::getTaskId, taskId)
                        .orderByAsc(Subtitle::getSeq).last("limit 20"));
        String best = null;
        for (Subtitle s : subs) {
            String t = s.getTargetText();
            if (t == null || t.isBlank()) continue;
            t = t.trim();
            if (t.length() >= 6) return t;          // 第一条够长的直接用
            if (best == null) best = t;             // 否则记下第一条非空作兜底
        }
        return best != null ? best : "字幕样式预览 Subtitle Preview";
    }

    private void writeFile(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException("写文件失败: " + e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
