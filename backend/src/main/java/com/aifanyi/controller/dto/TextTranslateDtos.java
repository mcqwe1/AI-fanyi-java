package com.aifanyi.controller.dto;

import java.time.LocalDateTime;
import java.util.List;

public class TextTranslateDtos {

    public record TextTranslateReq(String text, String targetLang) {
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

    /** 历史列表条目（轻量，不含正文大字段）。 */
    public record HistoryItem(
            Long id,
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
            List<Line> lines,
            String plainTarget,
            String model,
            long elapsedMs,
            int untranslatedLines,
            LocalDateTime createdAt
    ) {
    }
}
