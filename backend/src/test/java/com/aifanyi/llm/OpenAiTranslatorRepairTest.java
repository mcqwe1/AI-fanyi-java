package com.aifanyi.llm;

import com.aifanyi.config.AifanyiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 漏翻与错位修复的核心回填逻辑：applyTranslations 的子集映射、空译文守卫、对齐校验。
 *
 * <p>背景一（漏行）：实测 deepseek-v4-pro 一次漏 17/40 行，旧策略「部分成功就接受」让这些行
 * 静默保留原文——修复后补翻只发缺行子集，模型返回的 i 是子集内行号，必须经映射回填。
 *
 * <p>背景二（错位）：任务 258（61.7 分钟英语视频）里模型把译文<b>整体串位 2 行</b>还了回来，
 * 行号照样连号，代码看不出任何异常，575 条字幕里 130 条（22.6%）因此对不上原文。
 * 现在每条译文必须附带原文锚点 s，核对不上就当没译、交给补翻重发。
 */
class OpenAiTranslatorRepairTest {

    private final OpenAiTranslator translator =
            new OpenAiTranslator(new AifanyiProperties(), new ObjectMapper(),
                    new MtTranslateClient(new ObjectMapper()));

    private static String[] lines(String... s) {
        return s.clone();
    }

    // ──────────────── 子集映射与既有守卫（行为不变）────────────────

    @Test
    void 补翻子集的行号经映射回填到正确的全局行() {
        String[] result = lines("a", "b", "c", "d", "e");
        boolean[] filled = {true, false, true, false, true};
        // 补翻只发了缺的两行（全局 1 和 3），模型按子集行号 0/1 返回
        translator.applyTranslations(
                "{\"translations\":[{\"i\":0,\"t\":\"乙\"},{\"i\":1,\"t\":\"丁\"}]}",
                List.of("b", "d"), result, filled, List.of(1, 3));
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
                List.of("c", "e"), result, filled, List.of(2, 4));
        assertArrayEquals(lines("a", "b", "丙", "d", "戊"), result);
    }

    @Test
    void 空白译文不算成功_留给下一轮补翻() {
        String[] result = lines("a", "b");
        boolean[] filled = {false, false};
        translator.applyTranslations(
                "{\"translations\":[{\"i\":0,\"t\":\"  \"},{\"i\":1,\"t\":\"乙\"}]}",
                List.of("a", "b"), result, filled, List.of(0, 1));
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
                List.of("a", "b"), result, filled, List.of(0, 1));
        assertEquals("a", result[0]);          // 已填的行不覆盖
        assertEquals("乙", result[1]);
    }

    @Test
    void 模型漏行时只有缺行保持未填() {
        String[] result = lines("a", "b", "c");
        boolean[] filled = {false, false, false};
        translator.applyTranslations(
                "{\"translations\":[{\"i\":0,\"t\":\"甲\"},{\"i\":2,\"t\":\"丙\"}]}",
                List.of("a", "b", "c"), result, filled, List.of(0, 1, 2));
        assertTrue(filled[0]);
        assertFalse(filled[1]);                // 漏的行未填 → 触发补翻
        assertTrue(filled[2]);
        assertEquals("b", result[1]);
    }

    @Test
    void 畸形返回整体忽略_不污染结果() {
        String[] result = lines("a", "b");
        boolean[] filled = {false, false};
        translator.applyTranslations("这不是JSON", List.of("a", "b"), result, filled, List.of(0, 1));
        assertArrayEquals(lines("a", "b"), result);
        assertFalse(filled[0]);
        assertFalse(filled[1]);
    }

    // ──────────────── 锚点校验 ────────────────

    @Test
    void 锚点对得上_正常回填() {
        String[] result = lines("hello there friend", "goodbye my dear");
        boolean[] filled = {false, false};
        int drift = translator.applyTranslations(
                "{\"translations\":["
                        + "{\"i\":0,\"s\":\"hello there\",\"t\":\"你好啊朋友\"},"
                        + "{\"i\":1,\"s\":\"goodbye my\",\"t\":\"再见了亲爱的\"}]}",
                List.of("hello there friend", "goodbye my dear"), result, filled, List.of(0, 1));
        assertEquals(0, drift);
        assertArrayEquals(lines("你好啊朋友", "再见了亲爱的"), result);
    }

    /**
     * 线上真实故障的复现：模型串位 2 行——它翻的其实是后面那行，锚点也如实抄了后面那行。
     * 旧代码只看 i，会照单全收；现在锚点对不上，这些行必须<b>全部被拒</b>并保留原文待重发。
     */
    @Test
    void 真实故障_串位两行时锚点全部失配并保留原文() {
        List<String> src = List.of(
                "i'm um i'm a uh potato uh part of the potato religion",
                "I'm a, I'm a, the, uh, sisters of the potato, or I'm, um, you know, our lady of potato, um,",
                "I'm a potato priest, potato, Mr. Potato Head, hey, copyright, I shouldn't do that,",
                "oh, man, um, all right, well, so now we have a potato in three sections, um,",
                "and uh well i was really i was honestly i was kind of hoping the inside of this was");
        String[] result = src.toArray(new String[0]);
        boolean[] filled = new boolean[src.size()];
        // 每条声称的 i 与它实际翻译的行差 2：锚点抄的是 src[i+2]
        int drift = translator.applyTranslations(
                "{\"translations\":["
                        + "{\"i\":0,\"s\":\"I'm a potato priest\",\"t\":\"我是土豆神父\"},"
                        + "{\"i\":1,\"s\":\"oh, man, um, all right\",\"t\":\"哦，天哪，嗯，好吧\"},"
                        + "{\"i\":2,\"s\":\"and uh well i was really\",\"t\":\"而且，嗯，我其实\"}]}",
                src, result, filled, List.of(0, 1, 2, 3, 4));
        assertEquals(3, drift, "三条都串位了，应全部被拒");
        for (int i = 0; i < src.size(); i++) {
            assertFalse(filled[i], "第 " + i + " 行不该被标记已译");
            assertEquals(src.get(i), result[i], "第 " + i + " 行应保留原文待重发");
        }
    }

    @Test
    void 部分串位时_对得上的正常回填_对不上的留给补翻() {
        List<String> src = List.of("alpha bravo charlie", "delta echo foxtrot", "golf hotel india");
        String[] result = src.toArray(new String[0]);
        boolean[] filled = new boolean[3];
        int drift = translator.applyTranslations(
                "{\"translations\":["
                        + "{\"i\":0,\"s\":\"alpha bravo\",\"t\":\"甲乙丙\"},"
                        + "{\"i\":1,\"s\":\"golf hotel\",\"t\":\"庚辛壬\"},"     // 串位：抄的是第 2 行
                        + "{\"i\":2,\"s\":\"golf hotel\",\"t\":\"庚辛壬\"}]}",
                src, result, filled, List.of(0, 1, 2));
        assertEquals(1, drift);
        assertEquals("甲乙丙", result[0]);
        assertTrue(filled[0]);
        assertEquals("delta echo foxtrot", result[1]);   // 串位那行保留原文
        assertFalse(filled[1]);
        assertEquals("庚辛壬", result[2]);
        assertTrue(filled[2]);
    }

    @Test
    void 没给锚点的条目同样无从核对_留给补翻() {
        List<String> src = List.of("alpha bravo charlie", "delta echo foxtrot");
        String[] result = src.toArray(new String[0]);
        boolean[] filled = new boolean[2];
        int drift = translator.applyTranslations(
                "{\"translations\":["
                        + "{\"i\":0,\"s\":\"alpha bravo\",\"t\":\"甲乙丙\"},"
                        + "{\"i\":1,\"t\":\"丁戊己\"}]}",
                src, result, filled, List.of(0, 1));
        assertEquals(1, drift);
        assertTrue(filled[0]);
        assertFalse(filled[1]);
    }

    /**
     * 模型不理解锚点、把译文或别的东西塞进 s（一条都对不上请求内任何一行）：
     * 此时若按锚点全盘拒绝，会把本来正确的译文也丢光、白烧三轮补翻，比修复前更差。
     * 应识别出「这是不听话，不是串位」，退回启发式档照常回填。
     */
    @Test
    void 模型把译文塞进锚点字段_退回启发式而非全盘拒绝() {
        List<String> src = List.of("alpha bravo charlie", "delta echo foxtrot",
                "golf hotel india", "juliet kilo lima");
        String[] result = src.toArray(new String[0]);
        boolean[] filled = new boolean[4];
        int drift = translator.applyTranslations(
                "{\"translations\":["
                        + "{\"i\":0,\"s\":\"甲乙丙\",\"t\":\"甲乙丙\"},"
                        + "{\"i\":1,\"s\":\"丁戊己\",\"t\":\"丁戊己\"},"
                        + "{\"i\":2,\"s\":\"庚辛壬\",\"t\":\"庚辛壬\"},"
                        + "{\"i\":3,\"s\":\"癸子丑\",\"t\":\"癸子丑\"}]}",
                src, result, filled, List.of(0, 1, 2, 3));
        assertEquals(0, drift);
        assertArrayEquals(lines("甲乙丙", "丁戊己", "庚辛壬", "癸子丑"), result);
    }
}
