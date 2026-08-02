package com.aifanyi.media;

import com.aifanyi.asr.Segment;

import java.util.ArrayList;
import java.util.List;

/**
 * 找「VAD 说这里有人说话、ASR 却一条字幕都没出」的缺口（漏翻治理的核心）。
 *
 * <p><b>为什么需要它</b>：Whisper 的批量转写靠 VAD 门卫决定哪些音频进模型，
 * 门卫（无论阈值调多少）都会漏掉一部分轻声/气声段——被拦掉的音频模型根本没见过，
 * 句子从源头就不存在，编辑器里连轴都没有。实测 29 分钟轻声直播：阈值 0.5 与 0.3
 * <b>各漏各的</b>，调参数治不了本。治本的办法是转写完后对账：凡是「语音区间 − 字幕
 * 覆盖」剩下的缺口，把那段音频剪出来单独重转一遍补回去。
 *
 * <p>纯函数、无依赖，边界行为全部可单测。
 */
public final class SpeechGapFinder {

    private SpeechGapFinder() {
    }

    /**
     * 计算缺口。
     *
     * @param segs      已转写出的分段（乱序也行）
     * @param vadRegions VAD 语音区间 [startMs, endMs]，升序互不重叠（/vad 接口的输出契约）
     * @param minGapMs  短于此的缺口忽略（呼吸声/词间停顿，重转没有意义还费时）
     * @param padMs     每个缺口向两侧外扩的余量（切音频别贴边，词会被切一半）
     * @param mergeGapMs 相邻缺口间距小于此值时合并成一段（少切几刀，少发几次请求）
     * @return 缺口列表 [startMs, endMs]，升序
     */
    public static List<long[]> find(List<Segment> segs, List<long[]> vadRegions,
                                    long minGapMs, long padMs, long mergeGapMs) {
        if (vadRegions == null || vadRegions.isEmpty()) {
            return List.of();
        }
        // 字幕覆盖区间：排序 + 合并（分段可能重叠/乱序）
        List<long[]> covered = new ArrayList<>();
        if (segs != null) {
            List<long[]> raw = new ArrayList<>(segs.size());
            for (Segment s : segs) {
                if (s.endMs() > s.startMs()) {
                    raw.add(new long[]{s.startMs(), s.endMs()});
                }
            }
            raw.sort((a, b) -> Long.compare(a[0], b[0]));
            for (long[] iv : raw) {
                if (!covered.isEmpty() && iv[0] <= covered.get(covered.size() - 1)[1]) {
                    long[] last = covered.get(covered.size() - 1);
                    last[1] = Math.max(last[1], iv[1]);
                } else {
                    covered.add(new long[]{iv[0], iv[1]});
                }
            }
        }

        // 语音区间减去字幕覆盖 → 缺口碎片
        List<long[]> gaps = new ArrayList<>();
        int ci = 0;
        for (long[] region : vadRegions) {
            long cursor = region[0];
            while (cursor < region[1]) {
                // 推进到第一个可能相交的覆盖区间
                while (ci < covered.size() && covered.get(ci)[1] <= cursor) {
                    ci++;
                }
                if (ci >= covered.size() || covered.get(ci)[0] >= region[1]) {
                    gaps.add(new long[]{cursor, region[1]});   // 剩余整段都没覆盖
                    break;
                }
                long[] cov = covered.get(ci);
                if (cov[0] > cursor) {
                    gaps.add(new long[]{cursor, Math.min(cov[0], region[1])});
                }
                cursor = Math.max(cursor, cov[1]);
            }
            // ci 不需要回退：区间升序互不重叠，已跳过的覆盖段不可能与后续 region 相交
        }

        // 过滤过短 → 外扩 → 合并相邻
        List<long[]> padded = new ArrayList<>();
        for (long[] g : gaps) {
            if (g[1] - g[0] < minGapMs) {
                continue;
            }
            padded.add(new long[]{Math.max(0, g[0] - padMs), g[1] + padMs});
        }
        List<long[]> merged = new ArrayList<>();
        for (long[] g : padded) {
            if (!merged.isEmpty() && g[0] - merged.get(merged.size() - 1)[1] <= mergeGapMs) {
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], g[1]);
            } else {
                merged.add(g);
            }
        }
        return merged;
    }
}
