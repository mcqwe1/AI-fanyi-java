package com.aifanyi.tts;

/**
 * TTS 引擎抽象：一段文本 → 音频字节（mp3）。
 * 实现类注册为 Spring Bean 后由 {@link TtsProviderFactory} 按 name 路由。
 */
public interface TtsProvider {

    String name();

    /** 合成一段文本，返回 mp3 音频字节。失败抛 BizException。 */
    byte[] synthesize(String text, TtsConfig cfg, TtsOptions opts);
}
