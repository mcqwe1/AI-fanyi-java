package com.aifanyi.media;

import com.aifanyi.asr.Segment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HallucinationFilterTest {

    private static Segment seg(long s, long e, String t) {
        return new Segment(s, e, t);
    }

    @Test
    void goodnightFamilyBlacklisted() {
        List<Segment> out = HallucinationFilter.filter(List.of(
                seg(0, 1_000, "おやすみなさい"),
                seg(2_000, 3_000, "おやすみ。"),
                seg(4_000, 5_000, "晚安"),
                seg(6_000, 7_000, "Good night!"),
                seg(8_000, 9_000, "今日はいい天気ですね")));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).text()).isEqualTo("今日はいい天気ですね");
    }

    @Test
    void existingBlacklistStillWorks() {
        List<Segment> out = HallucinationFilter.filter(List.of(
                seg(0, 1_000, "ご視聴ありがとうございました"),
                seg(2_000, 3_000, "谢谢观看"),
                seg(4_000, 5_000, "正常内容")));
        assertThat(out).hasSize(1);
    }

    @Test
    void goodnightInsideLongerSentenceKept() {
        // 只有整句等于套话才删；夹在正文里的"晚安"不受影响
        List<Segment> out = HallucinationFilter.filter(List.of(
                seg(0, 2_000, "跟大家说一声晚安，我们明天早上八点再见")));
        assertThat(out).hasSize(1);
    }
}
