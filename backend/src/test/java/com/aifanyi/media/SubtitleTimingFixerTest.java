package com.aifanyi.media;

import com.aifanyi.asr.Segment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯函数单测：VAD 双向对齐 / 非语音幻觉丢弃 / 整体平移。
 * 常量含义见 SubtitleTimingFixer：前拉阈值 150ms、上限 1200ms、贴近要求 250ms；
 * 后移阈值 200ms；终点修剪阈值 500ms、尾巴 400ms；外扩阈值 300ms、上限 1500ms。
 */
class SubtitleTimingFixerTest {

    private static Segment seg(long s, long e) {
        return new Segment(s, e, "text");
    }

    private static List<long[]> regions(long[]... r) {
        return List.of(r);
    }

    // ---- alignToSpeech：起点 ----

    @Test
    void lateStartPulledBackToSpeechOnset() {
        // 语音 10.0s 开始，Whisper 晚标到 10.6s → 起点应拉回 10.0s
        List<Segment> out = SubtitleTimingFixer.alignToSpeech(
                List.of(seg(10_600, 15_000)), regions(new long[]{10_000, 15_000}));
        assertThat(out.get(0).startMs()).isEqualTo(10_000);
        assertThat(out.get(0).endMs()).isEqualTo(15_000);
    }

    @Test
    void backwardPullCappedAtMax() {
        // 语音区间从 1.0s 就开始，但回拉上限 1200ms：起点 5.0s 最多拉到 3.8s
        List<Segment> out = SubtitleTimingFixer.alignToSpeech(
                List.of(seg(5_000, 8_000)), regions(new long[]{1_000, 8_000}));
        assertThat(out.get(0).startMs()).isEqualTo(3_800);
    }

    @Test
    void continuousSpeechMakesSubtitlesContiguousWithoutOverlap() {
        // 同一语音区间跨两条字幕：前条终点外扩到后条起点前，后条起点被前条终点挡住不回拉
        List<Segment> out = SubtitleTimingFixer.alignToSpeech(
                List.of(seg(1_000, 4_600), seg(5_000, 8_000)),
                regions(new long[]{1_000, 8_000}));
        assertThat(out.get(0).endMs()).isEqualTo(4_990);
        assertThat(out.get(1).startMs()).isEqualTo(5_000);
        assertThat(out.get(1).endMs()).isEqualTo(8_000);
    }

    @Test
    void staleRegionFarBeforeStartDoesNotPull() {
        // 语音区间结束在起点前 800ms（超出贴近要求 250ms）→ 不是本段语音，不回拉
        List<Segment> out = SubtitleTimingFixer.alignToSpeech(
                List.of(seg(5_000, 8_000)), regions(new long[]{3_900, 4_200}));
        assertThat(out.get(0).startMs()).isEqualTo(5_000);
    }

    @Test
    void earlyStartSnappedForwardToOnset() {
        // Whisper 把开场噪声并进首段（起点 3.0s，实际 5.0s 才说话）→ 后移
        List<Segment> out = SubtitleTimingFixer.alignToSpeech(
                List.of(seg(3_000, 8_000)), regions(new long[]{5_000, 8_000}));
        assertThat(out.get(0).startMs()).isEqualTo(5_000);
    }

    @Test
    void tinyDeviationUntouched() {
        // 偏差在阈值内（早 100ms / 晚 100ms）不折腾
        List<Segment> out = SubtitleTimingFixer.alignToSpeech(
                List.of(seg(10_100, 12_000)), regions(new long[]{10_000, 12_000}));
        assertThat(out.get(0).startMs()).isEqualTo(10_100);
    }

    // ---- alignToSpeech：终点 ----

    @Test
    void lingeringEndTrimmedToSpeechEndPlusTail() {
        // 语音 3.0s 结束，字幕拖到 6.0s → 截到 3.4s（语音结束 + 400ms 尾巴）
        List<Segment> out = SubtitleTimingFixer.alignToSpeech(
                List.of(seg(1_000, 6_000)), regions(new long[]{1_000, 3_000}));
        assertThat(out.get(0).endMs()).isEqualTo(3_400);
    }

    @Test
    void cutShortEndExtendedWhileSpeechContinues() {
        // 语音到 5.0s，字幕 3.0s 就没了 → 外扩（上限 end+1500 → 4.5s）
        List<Segment> out = SubtitleTimingFixer.alignToSpeech(
                List.of(seg(1_000, 3_000)), regions(new long[]{1_000, 5_000}));
        assertThat(out.get(0).endMs()).isEqualTo(4_500);
    }

    @Test
    void extensionNeverOverlapsNextSubtitle() {
        // 外扩被下一条起点钳住
        List<Segment> out = SubtitleTimingFixer.alignToSpeech(
                List.of(seg(1_000, 3_000), seg(3_500, 6_000)),
                regions(new long[]{1_000, 6_000}));
        assertThat(out.get(0).endMs()).isEqualTo(3_490);
        assertThat(out.get(1).startMs()).isEqualTo(3_500);
    }

    @Test
    void noRegionsMeansUntouched() {
        List<Segment> in = List.of(seg(1_000, 2_000));
        assertThat(SubtitleTimingFixer.alignToSpeech(in, List.of())).isSameAs(in);
        assertThat(SubtitleTimingFixer.alignToSpeech(in, null)).isSameAs(in);
    }

    // ---- dropNonSpeech ----

    @Test
    void hallucinationInSilenceDropped() {
        // 幻觉段周围（±窗口）完全没有语音 → 丢弃；真实段保留
        List<Segment> out = SubtitleTimingFixer.dropNonSpeech(
                List.of(seg(4_000, 6_000), seg(20_000, 21_000)),
                regions(new long[]{3_800, 6_200}));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).startMs()).isEqualTo(4_000);
    }

    @Test
    void lateMarkedRealSegmentSurvivesDrop() {
        // 起点晚标 1 秒的真实段：窗口向前放宽 1200ms 后能碰到语音 → 保留
        List<Segment> out = SubtitleTimingFixer.dropNonSpeech(
                List.of(seg(11_000, 12_000)), regions(new long[]{9_800, 11_050}));
        assertThat(out).hasSize(1);
    }

    @Test
    void longSegmentOnlyDroppedOnZeroOverlap() {
        // >6s 的长段：有一丁点重叠就保留；零重叠才丢
        Segment longSeg = seg(10_000, 17_000);
        assertThat(SubtitleTimingFixer.dropNonSpeech(
                List.of(longSeg), regions(new long[]{16_900, 17_000}))).hasSize(1);
        assertThat(SubtitleTimingFixer.dropNonSpeech(
                List.of(longSeg), regions(new long[]{40_000, 41_000}))).isEmpty();
    }

    // ---- shiftAll ----

    @Test
    void shiftAllMovesEverySegment() {
        List<Segment> out = SubtitleTimingFixer.shiftAll(
                List.of(seg(1_000, 2_000), seg(3_000, 4_000)), 250);
        assertThat(out.get(0).startMs()).isEqualTo(1_250);
        assertThat(out.get(1).endMs()).isEqualTo(4_250);
        List<Segment> in = List.of(seg(1_000, 2_000));
        assertThat(SubtitleTimingFixer.shiftAll(in, 0)).isSameAs(in);
    }

    // ---- fix 回归 ----

    @Test
    void fixStillResolvesOverlapsAndMinDuration() {
        List<Segment> out = SubtitleTimingFixer.fix(List.of(
                new Segment(1_000, 3_000, "a"),
                new Segment(2_500, 2_600, "b")));
        assertThat(out.get(0).endMs()).isEqualTo(2_490);   // 钳到下一条起点之前
        assertThat(out.get(1).startMs()).isEqualTo(2_500);
        assertThat(out.get(1).endMs()).isEqualTo(3_000);   // 最短时长兜底
    }
}
