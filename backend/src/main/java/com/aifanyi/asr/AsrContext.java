package com.aifanyi.asr;

/**
 * 一次 ASR 调用的上下文：解析后的 apiKey 与可选 model（覆盖 provider 默认）。
 */
public record AsrContext(String apiKey, String model) {

    public static AsrContext of(String apiKey, String model) {
        return new AsrContext(apiKey, model);
    }
}
