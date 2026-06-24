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

            // 结束钳到下一段开始之前，避免重叠（下一句还没说就显示）
            if (i + 1 < segs.size()) {
                long nextStart = segs.get(i + 1).startMs();
                if (end > nextStart - GAP_MS) {
                    end = nextStart - GAP_MS;
                }
            }

            // 兜底最短时长
            if (end < start + MIN_DURATION_MS) {
                end = start + MIN_DURATION_MS;
            }

            out.add(new Segment(start, end, cur.text().trim()));
        }
        return out;
    }
}
