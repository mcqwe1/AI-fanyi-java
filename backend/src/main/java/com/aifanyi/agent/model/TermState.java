package com.aifanyi.agent.model;

/**
 * ⑦ 术语的处置去向（2026-08 置信度机制重构后的四档）。
 *
 * <p>旧版是「按 0~1 标量分卡两条线」，实测导致落库恒为 0——见 {@link com.aifanyi.agent.node.TermTriage}
 * 里的原因分析。现在由分诊器按<b>规则</b>直接给出去向，不再有中间分数。
 *
 * <p>与旧枚举的对应：VERIFIED→ACTIVE，PENDING/UNVERIFIED→CANDIDATE。
 * 库里存量行的 status 仍是旧字符串，读取侧一律用 {@link #parse} 兼容，不做数据迁移
 * ——术语的 enabled 列才是真正决定「这条参不参与翻译」的字段，status 只是给人看的标签。
 */
public enum TermState {
    /** 入库并<b>启用</b>：有权威佐证，或多方独立给出同一译法 */
    ACTIVE,
    /** 入库但<b>不启用</b>（备选）：确实是该固化的词，但暂无佐证，等用户在术语库里点头 */
    CANDIDATE,
    /** 不落库，仅本次注入：保证本片内前后一致，但不值得占用户术语库一行 */
    EPHEMERAL,
    /** 丢弃：原文/译法无效（空、超长成句） */
    DISCARD;

    /** 是否需要写进 glossary_term。 */
    public boolean persistent() {
        return this == ACTIVE || this == CANDIDATE;
    }

    /** 落库时 enabled 列取值——只有 ACTIVE 直接生效。 */
    public int enabledFlag() {
        return this == ACTIVE ? 1 : 0;
    }

    /** 是否参与本次翻译的 prompt 注入。 */
    public boolean usableNow() {
        return this != DISCARD;
    }

    /** 人话名（术语库页面与 LangSmith 分档明细共用）。 */
    public String zh() {
        return switch (this) {
            case ACTIVE -> "入库启用";
            case CANDIDATE -> "入库备选";
            case EPHEMERAL -> "仅本次使用";
            case DISCARD -> "丢弃";
        };
    }

    /**
     * 宽松解析：认识旧版枚举名，认不出的一律当 CANDIDATE（保守——
     * 宁可让用户多看一眼，也不要把来路不明的条目当成已启用）。
     */
    public static TermState parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return switch (s.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "ACTIVE", "VERIFIED" -> ACTIVE;
            case "CANDIDATE", "PENDING", "UNVERIFIED" -> CANDIDATE;
            case "EPHEMERAL" -> EPHEMERAL;
            case "DISCARD" -> DISCARD;
            default -> CANDIDATE;
        };
    }
}
