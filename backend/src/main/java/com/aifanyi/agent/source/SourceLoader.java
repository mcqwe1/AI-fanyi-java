package com.aifanyi.agent.source;

import com.aifanyi.agent.model.Chunk;
import com.aifanyi.agent.model.SourceDoc;
import com.aifanyi.asr.Segment;
import com.aifanyi.common.BizException;
import com.aifanyi.domain.MediaKind;
import com.aifanyi.entity.TranslationTask;
import com.aifanyi.media.CharsetSniffer;
import com.aifanyi.media.TextSplitter;
import com.aifanyi.service.MediaTranscribeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * ② 处理器链：把各类源文件转成统一中间态 {@link SourceDoc}。
 *
 * <p>三条链在此汇合，之后所有节点与文件类型彻底解耦：
 * <ul>
 *   <li>视频/音频 → 复用 {@link MediaTranscribeService}（抽音频→ASR→反幻觉→对轴）</li>
 *   <li>文本 → 编码嗅探 + 按行/句切块，无时间轴</li>
 * </ul>
 *
 * <p><b>垃圾源拦截是全流程唯一允许终止任务的地方</b>——无文本/乱码在这里提前失败，
 * 免得上游烧完 token 才发现源文件根本没内容。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourceLoader {

    /** 可翻译字符少于此数视为空源 */
    private static final int MIN_CHARS = 20;
    /** 替换字符（U+FFFD）占比超过此值视为乱码 */
    private static final double MAX_REPLACEMENT_RATIO = 0.30;

    private final MediaTranscribeService transcriber;

    /**
     * 载入并分片。
     *
     * @param onAudioDone 音频抽取完成回调（媒体链用，落 audioPath）
     * @param asrProgress ASR 进度回调
     */
    public SourceDoc load(TranslationTask task, Runnable onAudioDone, DoubleConsumer asrProgress) {
        MediaKind kind = MediaKind.valueOf(
                task.getMediaType() == null ? "VIDEO" : task.getMediaType());
        return kind == MediaKind.TEXT
                ? loadText(task, kind)
                : loadMedia(task, kind, onAudioDone, asrProgress);
    }

    /** 视频/音频链：转写结果即分片，天然带时间轴。 */
    private SourceDoc loadMedia(TranslationTask task, MediaKind kind,
                                Runnable onAudioDone, DoubleConsumer asrProgress) {
        List<Segment> segments = transcriber.transcribe(task, onAudioDone, asrProgress);
        List<Chunk> chunks = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            chunks.add(Chunk.timed(i, s.text(), s.startMs(), s.endMs()));
        }
        SourceDoc doc = SourceDoc.of(chunks, task.getSourceLang(), task.getTargetLang(), kind);
        guardGarbage(doc);
        return doc;
    }

    /** 文本链：编码嗅探 → 拆行 → 超长行按句切块。srt/vtt 只取字幕文本行。 */
    private SourceDoc loadText(TranslationTask task, MediaKind kind) {
        Path file = Path.of(task.getVideoPath());   // 字段名是历史遗留，文本任务存的是 .txt
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (Exception e) {
            throw new BizException("读取文本文件失败: " + e.getMessage());
        }
        String content = CharsetSniffer.decode(bytes);
        if (content == null || content.isBlank()) {
            throw new BizException("文件内容为空或无法识别编码");
        }
        String name = task.getOriginalFilename() == null ? "" : task.getOriginalFilename().toLowerCase();
        if (name.endsWith(".srt") || name.endsWith(".vtt")) {
            content = extractSubtitleText(content);
        }

        List<Chunk> chunks = new ArrayList<>();
        int idx = 0;
        for (String line : content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (line.isBlank()) {
                continue;                            // 空行不进分片（译文按原结构还原不适用于本链）
            }
            for (String seg : TextSplitter.splitLong(line)) {
                if (!seg.isBlank()) {
                    chunks.add(Chunk.plain(idx++, seg));
                }
            }
        }
        SourceDoc doc = SourceDoc.of(chunks, task.getSourceLang(), task.getTargetLang(), kind);
        guardGarbage(doc);
        return doc;
    }

    /**
     * 垃圾源拦截（架构②：唯一允许终止任务的点）。
     * 在③之前执行，所以坏源不会消耗任何 token。
     */
    private void guardGarbage(SourceDoc doc) {
        if (doc.chunks().isEmpty()) {
            throw new BizException("未能从源文件中提取到任何可翻译内容");
        }
        String all = doc.fullText();
        long letters = all.codePoints().filter(Character::isLetterOrDigit).count();
        if (letters < MIN_CHARS) {
            throw new BizException("源文件可翻译内容过少（仅 " + letters + " 个有效字符），请检查文件是否正确");
        }
        long bad = all.chars().filter(c -> c == 0xFFFD).count();
        if ((double) bad / all.length() > MAX_REPLACEMENT_RATIO) {
            throw new BizException("源文件编码无法识别（乱码率过高），请另存为 UTF-8 后重试");
        }
    }

    /** 从 srt/vtt 抽纯字幕文本（丢弃序号、时间轴、WEBVTT 头）。 */
    private static String extractSubtitleText(String content) {
        StringBuilder sb = new StringBuilder();
        for (String ln : content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String t = ln.strip();
            if (t.isEmpty() || t.equalsIgnoreCase("WEBVTT") || t.matches("\\d+") || t.contains("-->")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t);
        }
        return sb.toString();
    }
}
