package com.aifanyi.media;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TextSplitter 单测：切点优先级、不拆代理对、边界情形。
 * 这段逻辑原在 TextTranslateService 里无测试覆盖，抽出时补上。
 */
class TextSplitterTest {

    /** 短行原样返回，不切。 */
    @Test
    void shortLineUntouched() {
        List<String> out = TextSplitter.splitLong("这是一个短句。", 100);
        assertEquals(1, out.size());
        assertEquals("这是一个短句。", out.get(0));
    }

    /** 切块后拼回必须与原文完全一致（不丢字不重复）。 */
    @Test
    void piecesRejoinToOriginal() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("这是第").append(i).append("句话。");
        }
        String src = sb.toString();
        List<String> out = TextSplitter.splitLong(src, 100);
        assertTrue(out.size() > 1, "应当被切成多块");
        assertEquals(src, String.join("", out));
    }

    /** 优先在句末标点后断开（句点须落在 [minCut, max) 搜索窗口内）。 */
    @Test
    void prefersSentenceEnd() {
        // max=64 → minCut=16；句号放在第 40 字符处，落在搜索窗口内
        String line = "第一句话内容需要足够长才能落进切点搜索窗口里面去啊啊啊啊啊啊啊啊啊啊啊啊啊。"
                + "x".repeat(60);
        List<String> out = TextSplitter.splitLong(line, 64);
        assertTrue(out.size() > 1);
        assertTrue(out.get(0).endsWith("。"), "首块应断在句号后，实际: " + out.get(0));
    }

    /** 切点过于靠前（早于 minCut）时不采用，避免切出碎块。 */
    @Test
    void ignoresBoundaryBeforeMinCut() {
        // 句号在第 3 字符，远早于 minCut=10，应被忽略
        String line = "短句。" + "y".repeat(80);
        List<String> out = TextSplitter.splitLong(line, 40);
        assertEquals(line, String.join("", out));
        assertFalse(out.get(0).equals("短句。"), "过早的切点不应被采用");
    }

    /** 没有句末标点时退到次级停顿标点。 */
    @Test
    void fallsBackToSoftBreak() {
        String line = "aaaaaaaaaaaaaaaaaaaa，bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        List<String> out = TextSplitter.splitLong(line, 40);
        assertTrue(out.size() > 1);
        assertTrue(out.get(0).endsWith("，"), "应断在逗号后，实际: " + out.get(0));
    }

    /** '.' 只有后跟空白/行尾才算句末——小数点和缩写不能当切点。 */
    @Test
    void decimalPointIsNotSentenceEnd() {
        // 3.14 里的点后面是数字，不应被当作句末切点
        String line = "value is 3.14 and " + "z".repeat(80);
        List<String> out = TextSplitter.splitLong(line, 40);
        for (String piece : out) {
            assertFalse(piece.endsWith("3."), "小数点被误判为句末: " + piece);
        }
    }

    /** 绝不在代理对中间切开（emoji 会变乱码）。 */
    @Test
    void neverSplitsSurrogatePair() {
        String emoji = "😀";                      // 单个 emoji = 2 个 char
        String line = "a".repeat(39) + emoji + "b".repeat(60);
        List<String> out = TextSplitter.splitLong(line, 40);
        assertEquals(line, String.join("", out));
        for (String piece : out) {
            assertFalse(Character.isHighSurrogate(piece.charAt(piece.length() - 1)),
                    "块尾停在代理对高位，字符被拆坏: " + piece);
            assertFalse(Character.isLowSurrogate(piece.charAt(0)),
                    "块首是代理对低位，字符被拆坏: " + piece);
        }
    }

    /** 无任何可用切点时硬切，且不死循环。 */
    @Test
    void hardCutWhenNoBoundary() {
        String line = "x".repeat(300);
        List<String> out = TextSplitter.splitLong(line, 50);
        assertEquals(line, String.join("", out));
        assertTrue(out.size() >= 6);
        for (String piece : out) {
            assertFalse(piece.isEmpty(), "不应产生空块");
        }
    }

    @Test
    void emptyLineYieldsOneEmptyPiece() {
        List<String> out = TextSplitter.splitLong("", 100);
        assertEquals(1, out.size());
        assertEquals("", out.get(0));
    }
}
