package com.aifanyi.llm;

import java.util.List;

/**
 * 翻译抽象。输入按行的原文，输出与之等长、顺序一致的译文。
 */
public interface LlmTranslator {

    /**
     * 批量翻译。返回 List 必须与 sources 等长、顺序对应（保证字幕时间轴对齐）。
     *
     * @param sources    原文行
     * @param targetLang 目标语言（如 "中文"）
     * @param cfg        有效 LLM 配置（baseUrl/apiKey/model 等）
     */
    List<String> translate(List<String> sources, String targetLang, LlmConfig cfg);
}
