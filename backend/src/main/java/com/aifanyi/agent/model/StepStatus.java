package com.aifanyi.agent.model;

/** 一次 LLM 回合的自报状态（协议层，模型返回）。与运行层的 {@link SubAgentStatus} 区分。 */
public enum StepStatus {
    DONE,
    NEED_SEARCH,
    FAILED;

    public static StepStatus parse(String s) {
        if (s == null) {
            return FAILED;
        }
        try {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FAILED;
        }
    }
}
