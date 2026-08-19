package com.aifanyi.llm;

import java.util.List;
import java.util.Map;

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
    default List<String> translate(List<String> sources, String targetLang, LlmConfig cfg) {
        return translate(sources, targetLang, cfg, Map.of(), null);
    }

    /**
     * 带术语表的翻译：glossary 为 原文术语→指定译法 的映射，
     * 实现会把【当前批次文本中出现的】术语对照注入 prompt，强制按表翻译。
     */
    default List<String> translate(List<String> sources, String targetLang, LlmConfig cfg,
                                   Map<String, String> glossary) {
        return translate(sources, targetLang, cfg, glossary, null);
    }

    /**
     * 完整形态：术语表 + 翻译风格。
     *
     * @param stylePrompt 用户自定义翻译风格提示词（如"古风文雅"）；null/空白 = 不指定风格
     */
    default List<String> translate(List<String> sources, String targetLang, LlmConfig cfg,
                                   Map<String, String> glossary, String stylePrompt) {
        return translate(sources, targetLang, cfg, glossary, stylePrompt, TranslateHooks.none());
    }

    /**
     * 带旁路钩子的完整形态：可实时拿进度、可中途叫停。
     *
     * <p>有了 {@link TranslateHooks#onLineDone()}，调用方不必再为了刷进度条把任务
     * 切成一波一波地等——那种写法每波都要等最慢的一批
     * （实测 1778 行因此多花 16 秒，见 {@code DocTranslateService}）。
     */
    List<String> translate(List<String> sources, String targetLang, LlmConfig cfg,
                           Map<String, String> glossary, String stylePrompt,
                           TranslateHooks hooks);
}
