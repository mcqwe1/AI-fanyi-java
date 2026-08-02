package com.aifanyi.agent.model;

/**
 * 子 Agent 在 DAG 上的位置。<b>只能单调递增</b>——
 * SubAgentTask.advance() 会拒绝任何回退，从结构上杜绝「再搜一次」这类自由循环
 * （架构：内部固定DAG，无自由循环）。
 */
public enum Step {
    INIT,
    EXTRACTING,     // A 步骤进行中：提取候选 + 判定需联网
    EXTRACTED,
    SEARCHING,      // B 步骤：搜索（≤2 次）
    SEARCHED,
    RESOLVING,      // C 步骤：权威判定 + 证据 + 策略五选一
    DONE,
    WRAPPED_UP,     // 预算耗尽/超时，用已有结果收尾
    FAILED
}
