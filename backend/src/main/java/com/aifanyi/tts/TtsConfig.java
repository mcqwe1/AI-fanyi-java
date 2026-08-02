package com.aifanyi.tts;

/**
 * TTS 引擎有效配置（来自用户设置页，OpenAI 兼容 /audio/speech 端点）。
 */
public record TtsConfig(String baseUrl, String apiKey, String model) {
}
