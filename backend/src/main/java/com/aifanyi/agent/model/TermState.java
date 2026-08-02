package com.aifanyi.agent.model;

/**
 * ⑦ 术语状态机档位。
 * <p>阈值来自架构：≥0.85 入库可用 / 0.6~0.85 本次可用且待确认 / 0.4~0.6 仅本次注入 / <0.4 丢弃。
 */
public enum TermState {
    /** ≥0.85：入库并直接启用 */
    VERIFIED,
    /** 0.6~0.85：入库但标待确认，前端可一键确认/否决 */
    PENDING,
    /** 无权威答案的策略词：入库锁定全篇一致性（价值不在「正确」而在「统一」） */
    UNVERIFIED,
    /** 0.4~0.6：仅本次 prompt 注入，<b>不落库</b>——不拿低分猜测污染用户术语库 */
    EPHEMERAL,
    /** <0.4：丢弃 */
    DISCARD;

    /** 是否需要写进 glossary_term。 */
    public boolean persistent() {
        return this == VERIFIED || this == PENDING || this == UNVERIFIED;
    }

    /** 是否参与本次翻译的 prompt 注入。 */
    public boolean usableNow() {
        return this != DISCARD;
    }

    /** 人话名（LangSmith 分档明细用）。 */
    public String zh() {
        return switch (this) {
            case VERIFIED -> "已验证入库";
            case PENDING -> "待确认入库";
            case UNVERIFIED -> "策略词入库";
            case EPHEMERAL -> "仅本次使用";
            case DISCARD -> "丢弃";
        };
    }
}
