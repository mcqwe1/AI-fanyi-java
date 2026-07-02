package com.aifanyi.llm;

/**
 * 一次 Gemini 调用的有效配置（全部来自用户设置页，无环境变量兜底）。
 * baseUrl 可指向本机 vertex2openai 代理（OpenAI 兼容）或官方
 * OpenAI 兼容端点 https://generativelanguage.googleapis.com/v1beta/openai。
 */
public record GeminiConfig(
        String baseUrl,
        String apiKey,
        String model
) {
}
