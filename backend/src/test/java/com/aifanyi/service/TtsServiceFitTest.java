package com.aifanyi.service;

import com.aifanyi.entity.Subtitle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 配音槽位与变速倍率计算单测（TtsService 静态逻辑）。
 */
class TtsServiceFitTest {

    private static Subtitle sub(long startMs) {
        Subtitle s = new Subtitle();
        s.setStartMs(startMs);
        s.setEndMs(startMs + 100);
        return s;
    }

    @Test
    void fitTempoWithinSlotIsOne() {
        assertEquals(1.0, TtsService.fitTempo(5000, 10000));
        assertEquals(1.0, TtsService.fitTempo(5000, 5000));
        // 容差内的小溢出不变速
        assertEquals(1.0, TtsService.fitTempo(5100, 5000));
    }

    @Test
    void fitTempoScalesToSlot() {
        assertEquals(1.2, TtsService.fitTempo(6000, 5000), 1e-9);
    }

    @Test
    void fitTempoCappedAtMax() {
        assertEquals(1.4, TtsService.fitTempo(10000, 5000), 1e-9);
    }

    @Test
    void fitTempoInvalidSlotIsOne() {
        assertEquals(1.0, TtsService.fitTempo(5000, 0));
        assertEquals(1.0, TtsService.fitTempo(5000, -100));
    }

    @Test
    void slotRunsToNextLineStart() {
        List<Subtitle> subs = List.of(sub(0), sub(4000), sub(9000));
        assertEquals(4000, TtsService.slotMs(0, subs, 60000));
        assertEquals(5000, TtsService.slotMs(1, subs, 60000));
    }

    @Test
    void lastLineSlotRunsToVideoEnd() {
        List<Subtitle> subs = List.of(sub(0), sub(4000));
        assertEquals(56000, TtsService.slotMs(1, subs, 60000));
        // 视频时长未知（探测失败=0）→ 不限制
        assertEquals(Long.MAX_VALUE, TtsService.slotMs(1, subs, 0));
    }
}
