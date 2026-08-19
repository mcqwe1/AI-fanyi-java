package com.aifanyi.media;

import com.aifanyi.asr.Segment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SRT / WebVTT 解析：字幕文件 → {@link Segment}（毫秒时间轴 + 文本）。
 *
 * <p>给「已有字幕直翻」用：用户手里已经有原文字幕时，整条链路可以<b>跳过抽音频与语音识别</b>，
 * 直接拿这里解析出的分段去翻译。实测语音识别占视频翻译 90% 的耗时
 * （16.8 分钟视频里 126s / 140s），所以这一步等于把主链路砍掉九成。
 *
 * <p>解析策略刻意宽松——用户的字幕来源五花八门（下载的、剪辑软件导的、手写的）：
 * <ul>
 *   <li>序号行可有可无，不作为分块依据；只认「含 --&gt; 的时间轴行」</li>
 *   <li>同时吃 SRT 的 {@code 00:00:01,500} 与 VTT 的 {@code 00:00:01.500}，小时段可省略</li>
 *   <li>一条字幕的多行文本合并成一行（翻译按行对齐，保持换行只会把一句话拆成两句翻）</li>
 *   <li>WEBVTT 头、NOTE 块、cue 设置（{@code align:start position:50%}）一律忽略</li>
 * </ul>
 * 解析不出任何一条时返回空列表，由调用方决定报什么错。
 */
public final class SubtitleParser {

    private SubtitleParser() {
    }

    /** 时间轴行：起点 --> 终点，后面可能跟 VTT 的 cue 设置。 */
    private static final Pattern CUE = Pattern.compile(
            "^\\s*(\\d{1,2}:)?(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})\\s*-->\\s*"
                    + "(\\d{1,2}:)?(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})");

    /** VTT 里去掉 {@code <c.colorE5E5E5>} 这类内联标签，只留纯文本。 */
    private static final Pattern VTT_TAG = Pattern.compile("</?[a-zA-Z][^>]*>");

    /** 文件是不是字幕（按扩展名）。 */
    public static boolean isSubtitleFile(String filename) {
        if (filename == null) {
            return false;
        }
        String n = filename.toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".srt") || n.endsWith(".vtt");
    }

    /**
     * 解析字幕文本为分段，按起点升序。
     * 时间轴非法（终点不晚于起点）或文本为空的条目直接丢弃。
     */
    public static List<Segment> parse(String content) {
        List<Segment> out = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

        long startMs = -1;
        long endMs = -1;
        StringBuilder text = new StringBuilder();

        for (String raw : lines) {
            String line = raw.strip();
            Matcher m = CUE.matcher(line);
            if (m.find()) {
                // 撞上新的时间轴行：把上一条收掉，再开新的一条
                flush(out, startMs, endMs, text);
                startMs = ms(m.group(1), m.group(2), m.group(3), m.group(4));
                endMs = ms(m.group(5), m.group(6), m.group(7), m.group(8));
                text.setLength(0);
                continue;
            }
            if (startMs < 0) {
                continue;                       // 还没进入任何一条字幕：WEBVTT 头、NOTE、空行等
            }
            if (line.isEmpty()) {
                flush(out, startMs, endMs, text);
                startMs = -1;
                endMs = -1;
                text.setLength(0);
                continue;
            }
            if (line.matches("\\d+")) {
                continue;                       // 下一条的序号行（有些文件不空行就直接接序号）
            }
            String clean = VTT_TAG.matcher(line).replaceAll("").strip();
            if (clean.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');               // 一条字幕内的换行合并为空格，整条作为一行去翻
            }
            text.append(clean);
        }
        flush(out, startMs, endMs, text);
        out.sort(java.util.Comparator.comparingLong(Segment::startMs));
        return out;
    }

    private static void flush(List<Segment> out, long startMs, long endMs, StringBuilder text) {
        if (startMs < 0 || endMs <= startMs) {
            return;
        }
        String t = text.toString().strip();
        if (!t.isEmpty()) {
            out.add(new Segment(startMs, endMs, t));
        }
    }

    /** (可选小时)、分、秒、毫秒 → 毫秒。毫秒段可能只有 1~2 位（.5 = 500ms）。 */
    private static long ms(String hourGroup, String min, String sec, String milli) {
        long h = hourGroup == null || hourGroup.isEmpty()
                ? 0 : Long.parseLong(hourGroup.substring(0, hourGroup.length() - 1));
        long value = h * 3_600_000L
                + Long.parseLong(min) * 60_000L
                + Long.parseLong(sec) * 1_000L;
        String f = milli.length() >= 3 ? milli.substring(0, 3) : (milli + "000").substring(0, 3);
        return value + Long.parseLong(f);
    }
}
