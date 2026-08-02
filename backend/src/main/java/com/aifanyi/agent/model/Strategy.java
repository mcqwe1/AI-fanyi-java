package com.aifanyi.agent.model;

/**
 * 无权威答案时的译法策略（架构④的「策略模式五选一」+ 权威命中一档）。
 * <p>策略词的价值不是「记录正确答案」，而是<b>锁定全篇一致性</b>——
 * 同一个生造词全片译法统一，比每次重新猜更重要。故 ⑦ 状态机对策略词有特殊规则。
 */
public enum Strategy {
    /** 搜到权威/官方译名 */
    AUTHORITATIVE,
    /** 保留原文（如 API、iPhone） */
    KEEP_ORIGINAL,
    /** 音译 */
    TRANSLITERATE,
    /** 意译 */
    FREE,
    /** 音意结合 */
    SOUND_MEANING,
    /** 造词 */
    COINAGE;

    public static Strategy parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 是否为「无答案时自拟」的策略——⑦ 状态机据此决定是否强制入库锁一致性。 */
    public boolean isCoined() {
        return this != AUTHORITATIVE;
    }

    /** 人话名（术语库 note 与 LangSmith 上报共用）。 */
    public String zh() {
        return switch (this) {
            case AUTHORITATIVE -> "权威译名";
            case KEEP_ORIGINAL -> "保留原文";
            case TRANSLITERATE -> "音译";
            case FREE -> "意译";
            case SOUND_MEANING -> "音意结合";
            case COINAGE -> "造词";
        };
    }
}
