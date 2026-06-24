package com.aifanyi.asr;

/**
 * 一段带时间轴的转写结果（毫秒）。
 */
public record Segment(long startMs, long endMs, String text) {
}
