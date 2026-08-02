package com.aifanyi.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 预算与截止时刻单测（harness 约束层）。
 * 这些是「工具≤3次 / 搜索≤2次 / 墙钟30s」由代码而非模型保证的地方，必须锁死。
 */
class AgentBudgetTest {

    private static AgentBudget budget(long ms) {
        return AgentBudget.of(Deadline.in(Duration.ofMillis(ms)), 2, 3, 2);
    }

    @Test
    void llmStepsAreCapped() {
        AgentBudget b = budget(60_000);
        assertTrue(b.tryLlmStep());
        assertTrue(b.tryLlmStep());
        assertFalse(b.tryLlmStep(), "DAG 固定两步，第三步必须被拒");
        assertEquals(2, b.llmUsed());
    }

    @Test
    void requestsAllowOneRetryPerStep() {
        AgentBudget b = budget(60_000);
        // maxRequests = maxLlm * 2 = 4
        for (int i = 0; i < 4; i++) {
            assertTrue(b.tryRequest(), "第 " + (i + 1) + " 次请求应放行");
        }
        assertFalse(b.tryRequest(), "超出重试余量后必须拒绝");
    }

    @Test
    void searchIsCappedAtTwo() {
        AgentBudget b = budget(60_000);
        assertTrue(b.trySearch());
        assertTrue(b.trySearch());
        assertFalse(b.trySearch(), "搜索最多 2 次");
        assertEquals(2, b.searchUsed());
        // 搜索占用的是 tools 额度，还剩 1 次留作重试余量
        assertEquals(2, b.toolsUsed());
    }

    /** 封口后即使额度没用完也不许再搜——「查不到=换策略，而非继续搜」。 */
    @Test
    void sealBlocksFurtherSearch() {
        AgentBudget b = budget(60_000);
        assertTrue(b.trySearch());
        b.sealSearch();
        assertFalse(b.trySearch(), "封口后必须拒绝，哪怕还有额度");
    }

    @Test
    void expiredDeadlineBlocksEverything() {
        AgentBudget b = budget(0);
        assertFalse(b.tryLlmStep());
        assertFalse(b.tryRequest());
        assertFalse(b.trySearch());
    }

    @Test
    void deadlineRemainingNeverNegative() {
        Deadline d = Deadline.in(Duration.ofMillis(0));
        assertEquals(0, d.remainingMs());
        assertTrue(d.expired());
    }

    /** 子 deadline 取「全局剩余」与「单体上限」的更早者——全局兜底优先。 */
    @Test
    void earliestOfPrefersGlobalWhenGlobalIsSooner() {
        Deadline global = Deadline.in(Duration.ofMillis(500));
        Deadline sub = Deadline.earliestOf(global, Duration.ofSeconds(30));
        assertTrue(sub.remainingMs() <= 500, "全局只剩 500ms，子 deadline 不得超过它");
    }

    @Test
    void earliestOfPrefersCapWhenCapIsSooner() {
        Deadline global = Deadline.in(Duration.ofSeconds(300));
        Deadline sub = Deadline.earliestOf(global, Duration.ofMillis(800));
        assertTrue(sub.remainingMs() <= 800, "单体上限 800ms 更紧，应采用它");
    }

    /** hasRoomFor 是关键：剩 800ms 时 expired()=false，但不该再发 LLM 请求。 */
    @Test
    void hasRoomForGuardsAgainstDoomedCalls() {
        Deadline d = Deadline.in(Duration.ofMillis(800));
        assertFalse(d.expired(), "还没到期");
        assertFalse(d.hasRoomFor(6_000), "但不够跑一次 LLM，必须提前收尾");
        assertTrue(d.hasRoomFor(100));
    }

    /** 并发下计数器不得超发。 */
    @Test
    void countersAreThreadSafe() throws Exception {
        AgentBudget b = AgentBudget.of(Deadline.in(Duration.ofSeconds(60)), 100, 100, 50);
        int threads = 16;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var latch = new java.util.concurrent.CountDownLatch(threads);
        var granted = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    for (int j = 0; j < 20; j++) {
                        if (b.trySearch()) granted.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdownNow();
        assertEquals(50, granted.get(), "并发下放行次数必须精确等于额度，不能超发");
        assertEquals(50, b.searchUsed());
    }
}
