package com.aifanyi.agent.model;

/** 降级原因（落 Trace，便于事后区分「模型慢」与「没抢到线程」）。 */
public enum DegradeReason {
    /** 单个子 Agent 超过自身墙钟 */
    TIMEOUT,
    /** 全局 deadline 到点 */
    GLOBAL_DEADLINE,
    /** 预算（LLM 步骤/请求/工具次数）耗尽 */
    BUDGET,
    /** 线程被取消/中断 */
    CANCELLED,
    /** 抛异常 */
    ERROR,
    /** 全局到点时还没开始执行 */
    NOT_STARTED
}
