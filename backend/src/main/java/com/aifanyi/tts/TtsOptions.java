package com.aifanyi.tts;

/**
 * 单次合成参数：音色 / 语速（0.25~4.0，1.0=常速）。
 */
public record TtsOptions(String voice, double speed) {

    public TtsOptions {
        if (voice == null || voice.isBlank()) voice = "alloy";
        if (speed <= 0) speed = 1.0;
        speed = Math.max(0.25, Math.min(4.0, speed));
    }
}
