package com.aifanyi.controller.dto;

import java.time.LocalDateTime;
import java.util.List;

public class TextTranslateDtos {

    public record TextTranslateReq(String text, String targetLang, String stylePrompt) {
    }

    /** 文件翻译结果：内嵌常规翻译结果 + 译文文件的建议下载名与格式。 */
    public record FileTranslateResp(
            Long id,
            List<Line> lines,
            String plainTarget,
            String model,
            long elapsedMs,
            int untranslatedLines,
            String filename,       // 译文文件建议名（原名加 .译文 后缀）
            String format          // txt / srt / md
    ) {
    }

    /** 一行（或长行切出的一块）的原文/译文对。空白行占位（target 为空串）以保持段落结构。 */
    public record Line(String source, String target) {
    }

    public record TextTranslateResp(
            Long id,
            List<Line> lines,
            String plainTarget,
            String model,
            long elapsedMs,
            int untranslatedLines
    ) {
    }

    /** 历史列表条目（轻量，不含正文大字段）。channel 见 TextTranslation。 */
    public record HistoryItem(
            Long id,
            String channel,
            String targetLang,
            String preview,
            String model,
            long elapsedMs,
            int untranslatedLines,
            LocalDateTime createdAt
    ) {
    }

    /** 历史详情（含原文全文与对照数据，供回放/导出/重译）。 */
    public record HistoryDetail(
            Long id,
            String targetLang,
            String sourceText,
            String stylePrompt,
            List<Line> lines,
            String plainTarget,
            String model,
            long elapsedMs,
            int untranslatedLines,
            LocalDateTime createdAt
    ) {
    }
}
