package com.aifanyi.media;

import com.aifanyi.asr.Segment;

import java.util.ArrayList;
import java.util.List;

/**
 * 字幕时间轴后处理：修正 Whisper 等 ASR 常见的时间戳问题。
 * - 过滤空白段
 * - 去重叠：前一段 end 不得超过后一段 start（避免"下一句字幕提前出现"）
 * - 兜底最短时长，避免一闪而过
 */
public final class SubtitleTimingFixer {

    /** 字幕最短显示时长（毫秒） */
    private static final long MIN_DURATION_MS = 500;
    /** 两段之间留出的最小间隙（毫秒） */
    private static final long GAP_MS = 10;

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
