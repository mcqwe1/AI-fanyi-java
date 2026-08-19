package com.aifanyi.controller.dto;

import java.time.LocalDateTime;

/** 文档翻译模式 DTO。 */
public final class DocDtos {

    private DocDtos() {
    }

    /** 任务视图（列表/详情/轮询共用）。preview=内容预览（工作台/历史卡片按格式渲染）。 */
    public record DocItem(
            Long id,
            String filename,
            String format,
            String outputFormat,
            String preview,
            String targetLang,
            String status,
            int progress,
            int totalSegments,
            int doneSegments,
            String errorMsg,
            String outputName,
            String model,
            long elapsedMs,
            int untranslatedSegments,
            LocalDateTime createdAt) {
    }

    /** 原文-译文对照视图。 */
    public record DocCompare(
            Long id,
            String filename,
            String format,
            String targetLang,
            String model,
            java.util.List<SegmentPair> segments) {
    }

    /** 一个对照段：untranslated = 译文与原文相同（疑似未翻译）。 */
    public record SegmentPair(int idx, String source, String target, boolean untranslated) {
    }
}
