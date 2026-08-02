package com.aifanyi.agent;

import java.time.Duration;

/**
 * 截止时刻（harness 约束层）。
 * <p>用 {@link System#nanoTime()} 而非 currentTimeMillis：后者在 Windows 休眠/唤醒、
 * NTP 校时时会跳变，一跳就会把所有在跑的子 Agent 误判为超时。
 * <p>不可变、线程安全，可在多个子 Agent 间共享作全局 deadline。
 */
public final class Deadline {

    private final long deadlineNanos;

    private Deadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    /** 从此刻起 d 之后到期。 */
    public static Deadline in(Duration d) {
        return new Deadline(System.nanoTime() + Math.max(0, d.toNanos()));
    }

    /**
     * 取「全局剩余」与「单体上限」中更早的那个——全局兜底必须优先于单体上限，
     * 否则最后一个子 Agent 可以在全局到点后还独自跑满 30 秒。
     */
    public static Deadline earliestOf(Deadline global, Duration cap) {
        long capped = System.nanoTime() + Math.max(0, cap.toNanos());
        return new Deadline(Math.min(global.deadlineNanos, capped));
    }

    /** 剩余毫秒；已到期返回 0（不返回负数，调用方可直接用作超时值）。 */
    public long remainingMs() {
        long left = deadlineNanos - System.nanoTime();
        return left <= 0 ? 0 : left / 1_000_000L;
    }

    public boolean expired() {
        return System.nanoTime() >= deadlineNanos;
    }

    /**
     * 剩余时间是否够做一件预计耗时 needMs 的事。
     * <p>比裸 {@link #expired()} 更该用：剩 800ms 时 expired() 仍是 false，
     * 进去发一次 LLM 请求必然超时——白烧一次调用还拿不到结果。
     */
    public boolean hasRoomFor(long needMs) {
        return remainingMs() >= needMs;
    }

    @Override
    public String toString() {
        return "Deadline(remaining=" + remainingMs() + "ms)";
    }
}
