package com.aifanyi.agent.model;

/**
 * 子 Agent 实例的最终状态，<b>由代码判定，模型无权决定</b>。
 * <p>与 {@link StepStatus} 严格区分：架构图把两者混在一个 status 里
 * （DONE/NEED_SEARCH/FAILED），但 NEED_SEARCH 是「一次 LLM 回合的自报结果」，
 * 不是子 Agent 的终态——停在那里的子 Agent 其实什么都没产出。
 */
public enum SubAgentStatus {
    /** 正常跑完整个 DAG */
    DONE,
    /** 超时/预算耗尽，但带回了部分结果（架构：超限→已有结果收尾） */
    PARTIAL,
    /** 异常且无任何结果 */
    FAILED,
    /** 全局 deadline 到点时还没轮到执行 */
    SKIPPED
}
