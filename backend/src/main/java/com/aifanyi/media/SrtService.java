package com.aifanyi.media;

import com.aifanyi.entity.Subtitle;

import java.util.List;
import java.util.function.Function;

/**
 * 生成 SRT 字幕文本。
 */
public final class SrtService {

    private SrtService() {
    }

    /** 用译文生成 SRT；译文为空时回退到原文。 */
    public static String toSrt(List<Subtitle> subs) {
        return build(subs, s -> {
            String t = s.getTargetText();
            return (t == null || t.isBlank()) ? nullToEmpty(s.getSourceText()) : t;
        });
    }

    /** 双语 SRT：上译文下原文。 */
    public static String toBilingualSrt(List<Subtitle> subs) {
        return build(subs, s -> {
            String tgt = nullToEmpty(s.getTargetText());
            String src = nullToEmpty(s.getSourceText());
            return tgt.isBlank() ? src : tgt + "\n" + src;
        });
    }

    private static String build(List<Subtitle> subs, Function<Subtitle, String> textFn) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Subtitle s : subs) {
            sb.append(i++).append('\n')
              .append(formatTime(s.getStartMs())).append(" --> ").append(formatTime(s.getEndMs())).append('\n')
              .append(textFn.apply(s)).append('\n').append('\n');
        }
        return sb.toString();
    }

    /** 毫秒 -> HH:MM:SS,mmm */
    public static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long h = ms / 3_600_000;
        ms %= 3_600_000;
        long m = ms / 60_000;
        ms %= 60_000;
        long s = ms / 1000;
        long milli = ms % 1000;
        return String.format("%02d:%02d:%02d,%03d", h, m, s, milli);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
