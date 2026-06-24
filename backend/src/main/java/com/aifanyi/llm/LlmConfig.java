package com.aifanyi.llm;

/**
 * 一次翻译调用的有效配置（由用户设置解析，env 兜底）。
 */
public record LlmConfig(
        String baseUrl,
        String apiKey,
        String model,
        boolean disableThinking,
        int batchSize,
        int concurrency
) {
}
