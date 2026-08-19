package com.aifanyi.llm;

/**
 * 一次翻译调用的有效配置（由用户设置解析）。
 *
 * @param protocol   通信协议：openai（OpenAI 兼容 chat/completions）/ claude（Anthropic messages）/
 *                   deepl / google-mt / ms-mt（传统机器翻译 REST，不支持风格与术语提示）
 * @param timeoutSec 单次请求超时秒数（设置页「超时设置」）
 */
public record LlmConfig(
        String baseUrl,
        String apiKey,
        String model,
        String protocol,
        int timeoutSec,
        boolean disableThinking,
        int batchSize,
        int concurrency
) {

    public static final String PROTO_OPENAI = "openai";
    public static final String PROTO_CLAUDE = "claude";
    public static final String PROTO_DEEPL = "deepl";
    public static final String PROTO_GOOGLE_MT = "google-mt";
    public static final String PROTO_MS_MT = "ms-mt";

    /**
     * 兼容旧构造：默认 OpenAI 协议 + 60 秒超时。
     *
     * @deprecated <b>只允许在「本来就没有协议信息」的场景用</b>——即 user_setting 里那几组
     * 裸 base/key/model 配置（旧 llm_* 单配置、Agent 的翻译/主/子模型），它们在设置页
     * 就只支持 OpenAI 格式端点，没有协议可丢。
     * <p><b>已经持有一份 LlmConfig 时严禁用它重建</b>：那会把用户选的 claude / deepl /
     * google-mt / ms-mt 协议和自定义超时静默改回 openai + 60s。
     * 只想换批大小请用 {@link #withBatchSize(int)}。
     * <p>（2026-08 实测：文本/文档/划词三个模式就是这么把 Claude 用户的请求
     * 打到了 /chat/completions，而设置页「测试连接」却是对的。）
     */
    @Deprecated
    public LlmConfig(String baseUrl, String apiKey, String model,
                     boolean disableThinking, int batchSize, int concurrency) {
        this(baseUrl, apiKey, model, PROTO_OPENAI, 60, disableThinking, batchSize, concurrency);
    }

    /** 只换批大小，其余字段（尤其是 protocol 与 timeoutSec）原样保留。 */
    public LlmConfig withBatchSize(int newBatchSize) {
        return new LlmConfig(baseUrl, apiKey, model, protocol, timeoutSec,
                disableThinking, newBatchSize, concurrency);
    }

    /** 是否传统机器翻译协议（谷歌/微软/DeepL：无提示词能力，直接按段翻译）。 */
    public boolean isMt() {
        return PROTO_DEEPL.equals(protocol) || PROTO_GOOGLE_MT.equals(protocol) || PROTO_MS_MT.equals(protocol);
    }

    public boolean isClaude() {
        return PROTO_CLAUDE.equals(protocol);
    }

    /** 有效超时（秒）：配置缺失/非法时回退 60。 */
    public int effectiveTimeoutSec() {
        return timeoutSec >= 5 && timeoutSec <= 600 ? timeoutSec : 60;
    }
}
