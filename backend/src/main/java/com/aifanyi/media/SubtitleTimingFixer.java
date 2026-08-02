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
    /** 短段判幻觉的重叠下限：短促套话最像幻觉，用宽标准 */
    private static final long DROP_MIN_OVERLAP_MS = 250;
    /** 短段上限：此时长以内允许按「重叠不足」删；2~6s 之间只在完全零重叠时删 */
    private static final long DROP_SHORT_MS = 2000;
    /** 超过此时长一律不删：ASR 能转出连贯长句，本身就是比 VAD 更强的语音证据 */
    private static final long DROP_MAX_DURATION_MS = 6000;
    /** 熔断线：待删比例超过它 = VAD 对该素材不可靠（耳语/气声内容），整体放弃删除 */
    private static final double DROP_FUSE_RATIO = 0.25;
    /** 熔断的最小样本量：段太少时比例没有统计意义 */
    private static final int DROP_FUSE_MIN_SEGS = 20;

    private SubtitleTimingFixer() {
    }

    /**
     * 丢弃落在非语音区域的字幕段（文本无关的反幻觉）：Whisper 对呼吸/敲击/静音常幻觉出
     * “おやすみなさい/晚安/感谢观看”等整句，这类段在 VAD 下几乎无语音重叠。
     *
     * <p><b>但 VAD 说「没有语音」不是铁证</b>——Silero 靠声带振动特征识别，对耳语/气声
     * 内容几乎失聪（2026-08-01 实锤：轻声直播 326 条被判掉 190 条、占 58%，全是真台词，
     * Groq 转写得一清二楚）。所以删除是分级的、带熔断的：
     * <ul>
     *   <li>≤2s 短段：重叠不足 {@link #DROP_MIN_OVERLAP_MS} 才删（短促套话最像幻觉）；</li>
     *   <li>2~6s：完全零重叠才删；</li>
     *   <li>>6s 一律不删——ASR 能转出连贯长句，本身就是比 VAD 更强的语音证据；</li>
     *   <li><b>熔断</b>：待删比例超 {@link #DROP_FUSE_RATIO} 说明不是字幕有鬼、
     *       是 VAD 对该素材失聪，整体放弃删除，一条不动。</li>
     * </ul>
     * 原则：宁可多留一句幻觉（还有黑名单与复读折叠兜着），不可错杀一句台词。
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
            boolean drop;
            if (dur > DROP_MAX_DURATION_MS) {
                drop = false;                          // 长句免死
            } else if (dur > DROP_SHORT_MS) {
                drop = overlap == 0;
            } else {
                drop = overlap < DROP_MIN_OVERLAP_MS;
            }
            if (drop) {
                dropped.add(String.format("%s~%s 时长%dms 重叠%dms 「%s」",
                        ts(s.startMs()), ts(s.endMs()), dur, overlap, s.text()));
            } else {
                out.add(s);
            }
        }
        // 熔断：大面积「VAD 无语音」而 ASR 有连贯产出 → 前提不成立，全体放行
        if (segs.size() >= DROP_FUSE_MIN_SEGS
                && dropped.size() > segs.size() * DROP_FUSE_RATIO) {
            log.warn("VAD 非语音过滤熔断：{}/{} 条被判无语音（超过 {}%）——"
                            + "判定 VAD 对该素材不可靠（耳语/轻声内容常见），本次跳过零证据删除，一条不删",
                    dropped.size(), segs.size(), Math.round(DROP_FUSE_RATIO * 100));
            return segs;
        }
        if (!dropped.isEmpty()) {
            // 丢弃明细全量打印（漏翻排查的唯一线索：轴一起没了，事后无从追溯）。
            // 每条带时间戳与实际重叠量，便于判断是真幻觉还是短促台词被误杀。
            log.info("VAD 非语音过滤：丢弃 {} 条（{} → {} 条）",
                    dropped.size(), segs.size(), out.size());
            for (String d : dropped) {
                log.info("  [丢弃-VAD] {}", d);
            }
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
        List<String> dropped = new ArrayList<>();
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
            } else {
                dropped.add(String.format("%s~%s 时长%dms 「%s」",
                        ts(s.startMs()), ts(s.endMs()), dur, s.text()));
            }
        }
        if (!dropped.isEmpty()) {
            log.info("静音过滤（ffmpeg 兜底）：丢弃 {} 条（{} → {} 条）",
                    dropped.size(), segs.size(), out.size());
            for (String d : dropped) {
                log.info("  [丢弃-静音] {}", d);
            }
        }
        return out;
    }

    /** 毫秒转 mm:ss.SSS，方便对着视频核查。 */
    public static String ts(long ms) {
        return String.format("%02d:%02d.%03d", ms / 60000, (ms / 1000) % 60, ms % 1000);
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
