package com.aifanyi.media;

import com.aifanyi.domain.SubtitleStyle;
import com.aifanyi.entity.Subtitle;

import java.util.ArrayList;
import java.util.List;

/**
 * 把字幕 + 样式生成 ASS 文件内容（libass 烧录用）。
 * ASS 颜色为 &HAABBGGRR（注意 BGR 顺序），这里统一输出不透明（AA=00）。
 * 含智能换行：按视频宽度/字号估算每行容量，长行自动折行，避免溢出屏幕。
 */
public final class AssSubtitleWriter {

    private AssSubtitleWriter() {
    }

    /** 完整字幕 ASS */
    public static String build(List<Subtitle> subs, SubtitleStyle style, int w, int h, boolean bilingual) {
        StringBuilder sb = new StringBuilder();
        appendHeader(sb, style, w, h);
        int maxUnits = maxUnits(w, style.getFontSize());
        for (Subtitle s : subs) {
            String text = formatText(textOf(s, bilingual), maxUnits);
            sb.append("Dialogue: 0,")
              .append(toAssTime(s.getStartMs())).append(',')
              .append(toAssTime(s.getEndMs())).append(',')
              .append("Default,,0,0,0,,")
              .append(text).append('\n');
        }
        return sb.toString();
    }

    /** 单行样例 ASS（预览用），覆盖 0~30s */
    public static String buildSample(SubtitleStyle style, int w, int h, String sampleText) {
        StringBuilder sb = new StringBuilder();
        appendHeader(sb, style, w, h);
        int maxUnits = maxUnits(w, style.getFontSize());
        sb.append("Dialogue: 0,0:00:00.00,0:00:30.00,Default,,0,0,0,,")
          .append(formatText(sampleText, maxUnits)).append('\n');
        return sb.toString();
    }

    private static void appendHeader(StringBuilder sb, SubtitleStyle style, int w, int h) {
        int alignment = switch (upper(style.getPosition())) {
            case "TOP" -> 8;
            case "MIDDLE" -> 5;
            default -> 2; // BOTTOM
        };
        int borderStyle;
        double outline;
        double shadow;
        switch (upper(style.getEffect())) {
            case "SHADOW" -> { borderStyle = 1; outline = 1; shadow = 3; }
            case "BOX" -> { borderStyle = 3; outline = Math.max(2, style.getOutline()); shadow = 0; }
            default -> { borderStyle = 1; outline = style.getOutline(); shadow = 0; } // OUTLINE
        }
        int bold = style.isBold() ? -1 : 0;
        String primary = hexToAss(style.getPrimaryColor(), "FFFFFF");
        String outlineCol = hexToAss(style.getOutlineColor(), "000000");
        int marginV = 25;

        sb.append("[Script Info]\n")
          .append("ScriptType: v4.00+\n")
          .append("PlayResX: ").append(w).append('\n')
          .append("PlayResY: ").append(h).append('\n')
          .append("WrapStyle: 0\n\n")
          .append("[V4+ Styles]\n")
          .append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, ")
          .append("Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, ")
          .append("Alignment, MarginL, MarginR, MarginV, Encoding\n")
          .append("Style: Default,").append(safeFont(style.getFontName())).append(',')
          .append(style.getFontSize()).append(',')
          .append(primary).append(',')
          .append("&H000000FF,")
          .append(outlineCol).append(',')
          .append("&H64000000,")
          .append(bold).append(",0,0,0,100,100,0,0,")
          .append(borderStyle).append(',')
          .append(trim(outline)).append(',')
          .append(trim(shadow)).append(',')
          .append(alignment).append(",20,20,").append(marginV).append(",1\n\n")
          .append("[Events]\n")
          .append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
    }

    private static String textOf(Subtitle s, boolean bilingual) {
        String tgt = nz(s.getTargetText());
        String src = nz(s.getSourceText());
        if (tgt.isBlank()) tgt = src;
        if (bilingual && !src.isBlank()) {
            return tgt + "\n" + src;
        }
        return tgt;
    }

    // ---- 文本处理：转义 + 智能换行 ----

    /** 每行最多容纳的“显示单位”（中文=2，英文/数字=1）。按视频宽度与字号估算。 */
    private static int maxUnits(int w, int fontSize) {
        int fs = Math.max(1, fontSize);
        // 中文字宽 ≈ fontSize，用 0.9*w 作可用宽度；单位制下中文=2 单位
        int units = (int) Math.round(1.8 * w / (double) fs);
        return Math.max(12, units);
    }

    /** 处理一条字幕文本：去花括号、按 \n 分段、每段智能折行，最终用 \N 连接。 */
    private static String formatText(String t, int maxUnits) {
        if (t == null) return "";
        String cleaned = t.replace("\r\n", "\n").replace("{", "(").replace("}", ")").trim();
        String[] parts = cleaned.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append("\\N");
            out.append(wrap(parts[i], maxUnits));
        }
        return out.toString();
    }

    /** 把单行长文本按显示宽度折行（插入 \N）。中文按字断，英文尽量按词断。 */
    static String wrap(String line, int maxUnits) {
        if (line.isEmpty()) return line;
        List<String> tokens = tokenize(line);
        StringBuilder out = new StringBuilder();
        int cur = 0;
        for (String tok : tokens) {
            int tw = strUnits(tok);
            if (cur > 0 && cur + tw > maxUnits) {
                out.append("\\N");
                cur = 0;
                if (tok.equals(" ")) continue; // 行首不留空格
            }
            out.append(tok);
            cur += tw;
        }
        return out.toString();
    }

    /** 分词：中文等宽字符各自成 token；连续的英文/数字成词；空格单独成 token。 */
    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                if (word.length() > 0) { tokens.add(word.toString()); word.setLength(0); }
                tokens.add(" ");
            } else if (isWide(c)) {
                if (word.length() > 0) { tokens.add(word.toString()); word.setLength(0); }
                tokens.add(String.valueOf(c));
            } else {
                word.append(c);
            }
        }
        if (word.length() > 0) tokens.add(word.toString());
        return tokens;
    }

    private static int strUnits(String s) {
        int u = 0;
        for (int i = 0; i < s.length(); i++) u += charUnits(s.charAt(i));
        return u;
    }

    private static int charUnits(char c) {
        return isWide(c) ? 2 : 1;
    }

    /** 粗略判断是否为全角/CJK 字符（占 2 个显示单位）。 */
    private static boolean isWide(char c) {
        return c >= 0x1100 && (
                c <= 0x115F ||                       // 韩文字母
                (c >= 0x2E80 && c <= 0xA4CF) ||      // CJK 部首~汉字
                (c >= 0xAC00 && c <= 0xD7A3) ||      // 韩文音节
                (c >= 0xF900 && c <= 0xFAFF) ||      // CJK 兼容
                (c >= 0xFE30 && c <= 0xFE4F) ||      // CJK 兼容符号
                (c >= 0xFF00 && c <= 0xFF60) ||      // 全角 ASCII
                (c >= 0xFFE0 && c <= 0xFFE6));       // 全角符号
    }

    // ---- 通用 ----

    static String toAssTime(long ms) {
        if (ms < 0) ms = 0;
        long h = ms / 3_600_000; ms %= 3_600_000;
        long m = ms / 60_000;    ms %= 60_000;
        long s = ms / 1000;
        long cs = (ms % 1000) / 10;
        return String.format("%d:%02d:%02d.%02d", h, m, s, cs);
    }

    /** #RRGGBB -> &H00BBGGRR */
    static String hexToAss(String hex, String fallback) {
        String c = (hex == null ? "" : hex.trim());
        if (c.startsWith("#")) c = c.substring(1);
        if (!c.matches("(?i)[0-9a-f]{6}")) c = fallback;
        String rr = c.substring(0, 2);
        String gg = c.substring(2, 4);
        String bb = c.substring(4, 6);
        return ("&H00" + bb + gg + rr).toUpperCase();
    }

    private static String safeFont(String f) {
        return (f == null || f.isBlank()) ? "Microsoft YaHei" : f.replace(",", " ");
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String trim(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }
}
