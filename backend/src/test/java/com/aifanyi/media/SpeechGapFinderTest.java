package com.aifanyi.media;

import com.aifanyi.asr.Segment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SpeechGapFinder 单测：缺口计算是漏翻治理的地基，边界必须钉死。
 * 语义：缺口 = VAD 语音区间 − 字幕覆盖，过滤短碎片、两侧外扩、相邻合并。
 */
class SpeechGapFinderTest {

    private static Segment seg(long a, long b) {
        return new Segment(a, b, "x");
    }

    private static List<long[]> find(List<Segment> segs, List<long[]> vad) {
        // minGap=1000 / pad=200 / merge=500，测试用小参数好算
        return SpeechGapFinder.find(segs, vad, 1000, 200, 500);
    }

    /** 语音区间完全没被字幕覆盖 → 整段是缺口（带外扩）。 */
    @Test
    void wholeRegionUncovered() {
        List<long[]> gaps = find(List.of(seg(0, 1000)),
                List.of(new long[]{5000, 9000}));
        assertEquals(1, gaps.size());
        assertArrayEquals(new long[]{4800, 9200}, gaps.get(0), "两侧各外扩 200ms");
    }

    /** 字幕盖住区间中段 → 前后两个缺口。 */
    @Test
    void coverageInMiddleSplitsRegion() {
        List<long[]> gaps = find(List.of(seg(3000, 5000)),
                List.of(new long[]{0, 9000}));
        assertEquals(2, gaps.size());
        assertArrayEquals(new long[]{0, 3200}, gaps.get(0), "起点已在 0 不能再往左扩");
        assertArrayEquals(new long[]{4800, 9200}, gaps.get(1));
    }

    /** 全覆盖 → 无缺口。 */
    @Test
    void fullyCoveredNoGaps() {
        assertTrue(find(List.of(seg(0, 10000)), List.of(new long[]{1000, 9000})).isEmpty());
    }

    /** 低于 minGap 的碎缺口被忽略（呼吸/停顿不值得重转）。 */
    @Test
    void tinyGapsIgnored() {
        List<long[]> gaps = find(List.of(seg(0, 4000), seg(4800, 9000)),
                List.of(new long[]{0, 9000}));
        assertTrue(gaps.isEmpty(), "800ms 缺口低于 1000ms 门槛");
    }

    /** 相邻缺口外扩后相距很近 → 合并成一刀。 */
    @Test
    void nearbyGapsMerge() {
        // 覆盖把区间切成 [0,2000] [3400,5000] 两个缺口（都 ≥1000），
        // 外扩后 [0,2200] 与 [3200,5200] 相距 1000 > merge=500 → 不合并；
        // 换近一点的：[0,2000] 与 [2600,5000] 外扩后 [0,2200] [2400,5200] 距 200 ≤500 → 合并
        List<long[]> gaps = find(List.of(seg(2000, 2600)),
                List.of(new long[]{0, 5000}));
        assertEquals(1, gaps.size(), "外扩后相距 200ms 应合并成一刀");
        assertArrayEquals(new long[]{0, 5200}, gaps.get(0));
    }

    /** 多个 VAD 区间独立结算，字幕乱序/重叠也不影响。 */
    @Test
    void multipleRegionsAndUnsortedSegments() {
        List<long[]> gaps = find(
                List.of(seg(10000, 12000), seg(500, 3000), seg(2000, 4000)),   // 乱序 + 重叠
                List.of(new long[]{0, 4000}, new long[]{9000, 13000}, new long[]{20000, 22000}));
        // region1: 覆盖 [500,4000] → 缺口 [0,500) 太短忽略
        // region2: 覆盖 [10000,12000] → 缺口 [9000,10000] 和 [12000,13000]（各 1000，达标）
        // region3: 无覆盖 → 整段缺口
        assertEquals(3, gaps.size());
        assertArrayEquals(new long[]{8800, 10200}, gaps.get(0));
        assertArrayEquals(new long[]{11800, 13200}, gaps.get(1));
        assertArrayEquals(new long[]{19800, 22200}, gaps.get(2));
    }

    /** 空输入安全。 */
    @Test
    void emptyInputsSafe() {
        assertTrue(find(List.of(), List.of()).isEmpty());
        assertTrue(SpeechGapFinder.find(null, null, 1000, 200, 500).isEmpty());
        assertTrue(SpeechGapFinder.find(null, List.of(new long[]{0, 5000}), 1000, 200, 500)
                .size() == 1, "无任何字幕时整个语音区间都是缺口");
    }

    /** 实测场景还原：任务 225 里被 VAD 门卫吃掉的「17:16~17:19 今日あまり寝れてないから」。 */
    @Test
    void realWorldMissedLineScenario() {
        // 字幕在 17:14~17:15 和 17:24 后有，17:16~17:19 有语音但无字幕
        List<long[]> gaps = find(
                List.of(seg(1034000, 1035000), seg(1044000, 1047000)),
                List.of(new long[]{1034000, 1035000},
                        new long[]{1036000, 1039000},     // ← 被门卫吃掉的那句
                        new long[]{1044000, 1047000}));
        assertEquals(1, gaps.size());
        assertTrue(gaps.get(0)[0] <= 1036000 && gaps.get(0)[1] >= 1039000,
                "缺口必须完整包住漏掉的语音区间");
    }
}
