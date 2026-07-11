package com.aifanyi.media;

import com.aifanyi.asr.Segment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 字幕时间轴后处理：修正 Whisper 等 ASR 常见的时间戳问题。
 * 处理顺序（见 TaskPipeline）：
 *  1. dropNonSpeech —— 与 VAD 语音区间几乎零重叠的段判为幻觉直接丢弃（文本无关，
 *     比黑名单更通用；VAD 不可用时退回 dropSilenceHallucinations 的 ffmpeg 静音判定）
 *  2. alignToSpeech —— 双向对齐：早出的起点后移、晚出的起点前拉、终点修剪/外扩
 *  3. shiftAll —— 补回音视频流起始偏移（抽音频时丢失）
 *  4. fix —— 最终兜底：过滤空白、去重叠、保证最短时长
 */
@Slf4j
public final class SubtitleTimingFixer {

    /** 字幕最短显示时长（毫秒） */
    private static final long MIN_DURATION_MS = 500;
    /** 两段之间留出的最小间隙（毫秒） */
    private static final long GAP_MS = 10;

    // ---- alignToSpeech 参数 ----
    /** 起点后移（治早出）：语音起点比字幕起点晚超过此值才动 */
    private static final long FWD_SNAP_MIN_MS = 200;
    /** 起点前拉（治晚出）：语音起点比字幕起点早超过此值才动 */
    private static final long BACK_SNAP_MIN_MS = 150;
    /** 起点前拉上限：防止把字幕拉到 VAD 误判的远处噪声上 */
    private static final long BACK_SNAP_MAX_MS = 1200;
    /** 前拉要求语音区间到达字幕起点附近（区间尾距起点不超过此值），远古区间不算本段语音 */
    private static final long BACK_REACH_MS = 250;
    /** 终点拖进静音超过此值则修剪（治“字幕迟迟不消失”） */
    private static final long LINGER_MAX_MS = 500;
    /** 修剪后在语音结束点之后保留的阅读尾巴 */
    private static final long TAIL_MS = 400;
    /** 语音越过字幕终点超过此值则外扩终点（治“话没说完字幕先消失”） */
    private static final long EXTEND_MIN_MS = 300;
    /** 终点外扩上限 */
    private static final long EXTEND_MAX_MS = 1500;

    // ---- dropNonSpeech 参数 ----
    /** 重叠统计窗口向前放宽量（起点可能晚标，与 BACK_SNAP_MAX_MS 对齐） */
    private static final long DROP_WINDOW_BEFORE_MS = BACK_SNAP_MAX_MS;
    /** 重叠统计窗口向后放宽量 */
    private static final long DROP_WINDOW_AFTER_MS = 400;
    /** 窗口内语音重叠低于此值判为幻觉（VAD threshold=0.3 已很宽松，真实语音不会低于它） */
    private static final long DROP_MIN_OVERLAP_MS = 250;
    /** 超过此时长的段只在完全零重叠时才丢（长段幻觉少、误删代价大） */
    private static final long DROP_MAX_DURATION_MS = 6000;

    private SubtitleTimingFixer() {
    }

    /**
     * 丢弃落在非语音区域的字幕段（文本无关的反幻觉）：Whisper 对呼吸/敲击/静音常幻觉出
     * “おやすみなさい/晚安/感谢观看”等整句。真实语音在 VAD（threshold=0.3，宽松）下
     * 必有明显重叠；把段的时间窗前后放宽（容忍时间戳标偏）后仍几乎无语音重叠 → 幻觉。
     */
    public static List<Segment> dropNonSpeech(List<Segment> segs, List<long[]> regionsMs) {
        if (segs == null || segs.isEmpty() || regionsMs == null || regionsMs.isEmpty()) {
            return segs;
        }
        List<Segment> out = new ArrayList<>(segs.size());
        List<String> dropped = new ArrayList<>();
        for (Segment s : segs) {
            long overlap = overlapMs(regionsMs,
                    s.startMs() - DROP_WINDOW_BEFORE_MS, s.endMs() + DROP_WINDOW_AFTER_MS);
            long dur = s.endMs() - s.startMs();
            boolean drop = dur <= DROP_MAX_DURATION_MS
                    ? overlap < DROP_MIN_OVERLAP_MS
                    : overlap == 0;
            if (drop) {
                dropped.add(s.text());
            } else {
                out.add(s);
            }
        }
        if (!dropped.isEmpty()) {
            List<String> sample = dropped.subList(0, Math.min(5, dropped.size()));
            log.info("VAD 非语音过滤：丢弃 {} 条疑似幻觉字幕，如 {}", dropped.size(), sample);
        }
        return out;
    }

    /** [from, to) 与语音区间的重叠总毫秒数。regionsMs 按时间升序且互不重叠。 */
    private static long overlapMs(List<long[]> regionsMs, long from, long to) {
        long sum = 0;
        for (long[] r : regionsMs) {
            if (r[0] >= to) {
                break;
            }
            sum += Math.max(0, Math.min(r[1], to) - Math.max(r[0], from));
        }
        return sum;
    }

    /**
     * 丢弃静音区间里的字幕段（ffmpeg silencedetect 版，仅在 VAD 不可用时兜底）。
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
     * 把字幕起止时间双向对齐到 Silero VAD 的语音区间，治“早出 / 晚出 / 不消失 / 提前消失”：
     * <ul>
     *  <li>起点后移：Whisper 把开场噪声/静音并进首段导致字幕比说话早出 → 收紧到语音起点
     *      （区间起点已含 /vad 侧 speech_pad_ms=120 的提前量，天然留了入场余量）；</li>
     *  <li>起点前拉：Whisper 词级时间戳在音乐/静音后常晚标 0.5~2s 导致字幕比说话晚出 →
     *      拉回到覆盖（或几乎贴到）起点的语音区间起点，上限 {@link #BACK_SNAP_MAX_MS}，
     *      且不早于上一条对齐后的终点（连续说话时两条字幕自然衔接）；</li>
     *  <li>终点修剪：字幕拖进静音超过 {@link #LINGER_MAX_MS} → 截到语音结束 + 阅读尾巴；</li>
     *  <li>终点外扩：语音明显未完字幕先结束 → 顺延到语音结束（有上限、不压下一条）。</li>
     * </ul>
     * 只动时间、绝不动文本；段内找不到语音区间则原样保留（是否为幻觉由 dropNonSpeech 决定）。
     *
     * @param regionsMs VAD 语音区间 [startMs, endMs]（升序、不重叠）；null/空则原样返回
     */
    public static List<Segment> alignToSpeech(List<Segment> segs, List<long[]> regionsMs) {
        if (segs == null || segs.isEmpty() || regionsMs == null || regionsMs.isEmpty()) {
            return segs;
        }
        List<Segment> out = new ArrayList<>(segs.size());
        int fwd = 0;
        int back = 0;
        int trimmed = 0;
        int extended = 0;
        long fwdShift = 0;
        long backShift = 0;
        for (int i = 0; i < segs.size(); i++) {
            Segment s = segs.get(i);
            long start = s.startMs();
            long end = s.endMs();
            long prevBound = out.isEmpty() ? 0 : out.get(out.size() - 1).endMs() + GAP_MS;
            long nextStart = i + 1 < segs.size() ? segs.get(i + 1).startMs() : Long.MAX_VALUE;

            // ---- 起点：找第一个尾部越过“可用窗口起点”的语音区间 ----
            long searchFrom = Math.max(prevBound, start - BACK_SNAP_MAX_MS);
            int p = firstRegionEndingAfter(regionsMs, searchFrom);
            if (p < regionsMs.size() && regionsMs.get(p)[0] < end) {
                long[] r = regionsMs.get(p);
                long onset = Math.max(r[0], searchFrom);
                if (onset > start + FWD_SNAP_MIN_MS) {
                    // 早出 → 后移到真实说话点；不越过下一条起点，避免 fix() 把本条钳成闪现
                    long ns = Math.min(onset, end - MIN_DURATION_MS);
                    if (nextStart != Long.MAX_VALUE) {
                        ns = Math.min(ns, nextStart - GAP_MS);
                    }
                    if (ns > start) {
                        fwd++;
                        fwdShift += ns - start;
                        start = ns;
                    }
                } else if (onset < start - BACK_SNAP_MIN_MS && r[1] > start - BACK_REACH_MS) {
                    // 晚出 → 语音早已开始（区间覆盖或几乎贴到起点）而字幕未出，前拉
                    back++;
                    backShift += start - onset;
                    start = onset;
                }
            }

            // ---- 终点：以对齐后的 [start, end) 找最后一个相交语音区间 ----
            long speechEnd = lastSpeechEndWithin(regionsMs, start, end);
            if (speechEnd >= 0) {
                if (speechEnd > end + EXTEND_MIN_MS) {
                    long ne = Math.min(speechEnd, end + EXTEND_MAX_MS);
                    if (nextStart != Long.MAX_VALUE) {
                        ne = Math.min(ne, nextStart - GAP_MS);
                    }
                    if (ne > end) {
                        extended++;
                        end = ne;
                    }
                } else if (speechEnd < end - LINGER_MAX_MS) {
                    long ne = Math.max(speechEnd + TAIL_MS, start + MIN_DURATION_MS);
                    if (ne < end) {
                        trimmed++;
                        end = ne;
                    }
                }
            }

            out.add(start == s.startMs() && end == s.endMs() ? s : new Segment(start, end, s.text()));
        }
        if (fwd + back + trimmed + extended > 0) {
            log.info("VAD 时间轴对齐：起点后移 {} 条(共 {}s)、前拉 {} 条(共 {}s)，终点修剪 {} 条、外扩 {} 条",
                    fwd, String.format(Locale.ROOT, "%.1f", fwdShift / 1000.0),
                    back, String.format(Locale.ROOT, "%.1f", backShift / 1000.0),
                    trimmed, extended);
        }
        return out;
    }

    /** 二分：返回第一个 end &gt; key 的区间下标（区间不重叠 → 按 end 也有序）；无则 size。 */
    private static int firstRegionEndingAfter(List<long[]> regionsMs, long key) {
        int lo = 0;
        int hi = regionsMs.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (regionsMs.get(mid)[1] > key) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    /** 与 [from, to) 相交的最后一个语音区间的 end；无相交返回 -1。可能大于 to（语音未完）。 */
    private static long lastSpeechEndWithin(List<long[]> regionsMs, long from, long to) {
        long speechEnd = -1;
        for (int q = firstRegionEndingAfter(regionsMs, from); q < regionsMs.size(); q++) {
            if (regionsMs.get(q)[0] >= to) {
                break;
            }
            speechEnd = regionsMs.get(q)[1];
        }
        return speechEnd;
    }

    /**
     * 全体平移 offsetMs（用于补回“音频流相对视频流”的起始偏移：抽出的纯音频丢失了
     * 容器里的 start_time 差，不补回则整条轴相对视频恒定提前/滞后）。负值可能产生
     * 负起点，由后续 fix() 钳回 0。
     */
    public static List<Segment> shiftAll(List<Segment> segs, long offsetMs) {
        if (segs == null || segs.isEmpty() || offsetMs == 0) {
            return segs;
        }
        List<Segment> out = new ArrayList<>(segs.size());
        for (Segment s : segs) {
            out.add(new Segment(s.startMs() + offsetMs, s.endMs() + offsetMs, s.text()));
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
