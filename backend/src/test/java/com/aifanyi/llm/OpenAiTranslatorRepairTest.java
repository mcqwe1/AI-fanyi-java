package com.aifanyi.llm;

import com.aifanyi.config.AifanyiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 漏翻修复的核心回填逻辑：applyTranslations 的子集映射与空译文守卫。
 * <p>背景：实测 deepseek-v4-pro 一次漏 17/40 行，旧策略「部分成功就接受」让这些行
 * 静默保留原文——修复后补翻只发缺行子集，模型返回的 i 是子集内行号，必须经映射回填。
 */
class OpenAiTranslatorRepairTest {

    private final OpenAiTranslator translator =
            new OpenAiTranslator(new AifanyiProperties(), new ObjectMapper());

    private static String[] lines(String... s) {
        return s.clone();
    }

    @Test
    void 补翻子集的行号经映射回填到正确的全局行() {
        String[] result = lines("a", "b", "c", "d", "e");
        boolean[] filled = {true, false, true, false, true};
        // 补翻只发了缺的两行（全局 1 和 3），模型按子集行号 0/1 返回
        translator.applyTranslations(
                "{\"translations\":[{\"i\":0,\"t\":\"乙\"},{\"i\":1,\"t\":\"丁\"}]}",
                result, filled, List.of(1, 3));
        assertArrayEquals(lines("a", "乙", "c", "丁", "e"), result);
        assertTrue(filled[1]);
        assertTrue(filled[3]);
    }

    @Test
    void 纯字符串数组按位置对齐子集() {
        String[] result = lines("a", "b", "c", "d", "e");
        boolean[] filled = {true, true, false, true, false};
        translator.applyTranslations(
                "{\"translations\":[\"丙\",\"戊\"]}",
                result, filled, List.of(2, 4));
        assertArrayEquals(lines("a", "b", "丙", "d", "戊"), result);
    }

    @Test
    void 空白译文不算成功_留给下一轮补翻() {
        String[] result = lines("a", "b");
        boolean[] filled = {false, false};
        translator.applyTranslations(
                "{\"translations\":[{\"i\":0,\"t\":\"  \"},{\"i\":1,\"t\":\"乙\"}]}",
                result, filled, List.of(0, 1));
        assertEquals("a", result[0]);          // 空白被拒，原文保留
        assertFalse(filled[0]);                // 未标已填 → 下一轮会重发这行
        assertEquals("乙", result[1]);
        assertTrue(filled[1]);
    }

    @Test
    void 越界行号与已填行不受影响() {
        String[] result = lines("a", "b");
        boolean[] filled = {true, false};
        translator.applyTranslations(
                "{\"translations\":[{\"i\":0,\"t\":\"不许覆盖\"},{\"i\":5,\"t\":\"越界\"},{\"i\":1,\"t\":\"乙\"}]}",
                result, filled, List.of(0, 1));
        assertEquals("a", result[0]);          // 已填的行不覆盖
        assertEquals("乙", result[1]);
    }

    @Test
    void 模型漏行时只有缺行保持未填() {
        String[] result = lines("a", "b", "c");
        boolean[] filled = {false, false, false};
        translator.applyTranslations(
                "{\"translations\":[{\"i\":0,\"t\":\"甲\"},{\"i\":2,\"t\":\"丙\"}]}",
                result, filled, List.of(0, 1, 2));
        assertTrue(filled[0]);
        assertFalse(filled[1]);                // 漏的行未填 → 触发补翻
        assertTrue(filled[2]);
        assertEquals("b", result[1]);
    }

    @Test
    void 畸形返回整体忽略_不污染结果() {
        String[] result = lines("a", "b");
        boolean[] filled = {false, false};
        translator.applyTranslations("这不是JSON", result, filled, List.of(0, 1));
        assertArrayEquals(lines("a", "b"), result);
        assertFalse(filled[0]);
        assertFalse(filled[1]);
    }
}
