package com.aifanyi.media;

import com.aifanyi.asr.Segment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 字幕时间轴后处理：修正 Whisper 等 ASR 常见的时间戳问题。
 * - 过滤空白段
 * - 去重叠：前一段 end 不得超过后一段 start（避免"下一句字幕提前出现"）
 * - 兜底最短时长，避免一闪而过
 */
@Slf4j
public final class SubtitleTimingFixer {

    /** 字幕最短显示时长（毫秒） */
    private static final long MIN_DURATION_MS = 500;
    /** 两段之间留出的最小间隙（毫秒） */
    private static final long GAP_MS = 10;
    /** 起点吸附：提前量小于此不折腾（毫秒） */
    private static final long SNAP_MIN_SHIFT_MS = 300;

    private SubtitleTimingFixer() {
    }

    /**
     * 丢弃静音区间里的字幕段（Whisper 在静音处常幻觉出"感谢观看/you"等）。
     * 安全判定（避免误删真实语音）：
     *  - 段完全落在某个静音区间内 → 丢弃；或
     *  - 段较短（<3s）且中点落在静音区间内 → 丢弃。
     * 较长的段（真实语音）即使部分压到静音也保留。
     */
    public static List<Segment> dropSilenceHallucinations(List<Segment> segs, List<long[]> silence) {
        if (silence == null || silence.isEmpty() || segs == null) {
            return segs;
        }
        final long shortMs = 3000;
        List<Segment> out = new ArrayList<>();
        for (Segment s : segs) {
            long start = s.startMs();
            long end = s.endMs();
            long mid = (start + end) / 2;
            long dur = end - start;
            boolean drop = false;
            for (long[] iv : silence) {
                boolean fullyInside = start >= iv[0] && end <= iv[1];
                boolean shortMidInside = dur < shortMs && mid >= iv[0] && mid <= iv[1];
                if (fullyInside || shortMidInside) {
                    drop = true;
                    break;
                }
            }
            if (!drop) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 把字幕起点吸附到段内首个语音区间（Silero VAD）的起点，治“字幕比说话早出现”：
     * Whisper 会把开场噪声/静音并进首段（词级时间戳也被对齐抹早），此处用 VAD 的
     * 真实说话点前移收紧。只前移起点、绝不动文本与结束时间；段内找不到语音区间则不动。
     * 区间起点已含 /vad 侧 speech_pad_ms=120 的提前量，这里不再叠加。
     *
     * @param regionsMs VAD 语音区间 [startMs, endMs]（升序）；null/空则原样返回
     */
    public static List<Segment> snapToSpeechOnsets(List<Segment> segs, List<long[]> regionsMs) {
        if (segs == null || segs.isEmpty() || regionsMs == null || regionsMs.isEmpty()) {
            return segs;
        }
        List<Segment> out = new ArrayList<>(segs.size());
        int snapped = 0;
        long totalShift = 0;
        int p = 0;                                  // 区间游标：两列都按时间升序，单调前进即可
        long prevSearchFrom = Long.MIN_VALUE;
        for (int i = 0; i < segs.size(); i++) {
            Segment s = segs.get(i);
            long start = s.startMs();
            long end = s.endMs();
            // 起点若被拖早到上一条的语音里，从上一条结束处往后找，才能找到本条真正的说话点
            long searchFrom = i > 0 ? Math.max(start, segs.get(i - 1).endMs()) : start;
            if (searchFrom < prevSearchFrom) {
                p = 0;                              // 输入时间乱序时兜底重扫
            }
            prevSearchFrom = searchFrom;
            while (p < regionsMs.size() && regionsMs.get(p)[1] <= searchFrom) {
                p++;
            }
            long firstOnset = (p < regionsMs.size() && regionsMs.get(p)[0] < end)
                    ? regionsMs.get(p)[0] : -1;
            if (firstOnset > start + SNAP_MIN_SHIFT_MS) {
                long newStart = Math.min(firstOnset, end - MIN_DURATION_MS);
                if (i + 1 < segs.size()) {
                    // 不越过下一条的起点，否则 fix() 会把本条钳成 1ms 闪现
                    newStart = Math.min(newStart, segs.get(i + 1).startMs() - GAP_MS);
                }
                if (newStart > start) {
                    totalShift += newStart - start;
                    snapped++;
                    start = newStart;
                }
            }
            out.add(start == s.startMs() ? s : new Segment(start, end, s.text()));
        }
        if (snapped > 0) {
            log.info("VAD 起点吸附：收紧 {} 条字幕起点，共前移 {} 秒",
                    snapped, String.format(Locale.ROOT, "%.1f", totalShift / 1000.0));
        }
        return out;
    }

    public static List<Segment> fix(List<Segment> input) {
        List<Segment> segs = new ArrayList<>();
        for (Segment s : input) {
            if (s.text() != null && !s.text().isBlank()) {
                segs.add(s);
            }
        }
        if (segs.isEmpty()) {
            return segs;
        }

        List<Segment> out = new ArrayList<>(segs.size());
        for (int i = 0; i < segs.size(); i++) {
            Segment cur = segs.get(i);
            long start = Math.max(0, cur.startMs());
            long end = cur.endMs();

            // 起始不早于上一段结束 + 间隙
            if (!out.isEmpty()) {
                long prevEnd = out.get(out.size() - 1).endMs();
                if (start < prevEnd + GAP_MS) {
                    start = prevEnd + GAP_MS;
                }
            }

            // 先按理想最短时长兜底
            if (end < start + MIN_DURATION_MS) {
                end = start + MIN_DURATION_MS;
            }

            // 再钳到下一段开始之前——“防重叠/防提前出现”优先于最短时长
            if (i + 1 < segs.size()) {
                long nextStart = segs.get(i + 1).startMs();
                if (end > nextStart - GAP_MS) {
                    end = nextStart - GAP_MS;
                }
            }

            // 保证时长为正
            if (end <= start) {
                end = start + 1;
            }

            out.add(new Segment(start, end, cur.text().trim()));
        }
        return out;
    }
}
