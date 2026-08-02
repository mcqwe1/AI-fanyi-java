package com.aifanyi.asr;

import java.util.function.DoubleConsumer;

/**
 * 一次 ASR 调用的上下文：解析后的 apiKey、可选 model（覆盖 provider 默认）、
 * 可选进度回调（0~1，转写是流水线里最长的一段，回报给前端进度条免得像卡死）。
 */
public record AsrContext(String apiKey, String model, DoubleConsumer progress) {

    public AsrContext(String apiKey, String model) {
        this(apiKey, model, null);
    }

    public static AsrContext of(String apiKey, String model) {
        return new AsrContext(apiKey, model);
    }

    /** 安全回报进度（回调缺省或抛错都不影响转写主流程）。 */
    public void reportProgress(double ratio) {
        if (progress != null) {
            try {
                progress.accept(Math.max(0, Math.min(1, ratio)));
            } catch (Exception ignored) {
            }
        }
    }
}
