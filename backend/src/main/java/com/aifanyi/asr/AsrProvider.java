package com.aifanyi.asr;

import java.nio.file.Path;
import java.util.List;

/**
 * ASR 统一抽象。不同 provider（Groq / 本地 Whisper / Qwen / GLM）实现该接口，
 * 由前端选择、AsrProviderFactory 路由，AsrContext 携带每用户解析出的 key/model。
 */
public interface AsrProvider {

    /** provider 标识，对应前端下拉值的基名，如 "groq" */
    String name();

    /**
     * 转写音频为带时间轴的分段。
     *
     * @param audio    音频文件
     * @param language 源语言（如 "en"），null/"auto" 表示自动检测
     * @param ctx      解析后的 apiKey 与可选 model
     */
    List<Segment> transcribe(Path audio, String language, AsrContext ctx);
}
