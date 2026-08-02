package com.aifanyi.media;

import java.util.ArrayList;
import java.util.List;

/**
 * 长文本按句子边界切块（纯函数）。
 * 从 TextTranslateService 抽出，供文本翻译与 Agent 文本链共用。
 * 切点优先级：句末标点 → 次级停顿标点/空格 → 硬切；绝不拆开代理对（emoji 等）。
 */
public final class TextSplitter {

    /** 单块字符上限：超长行按句子边界切块，避免单批 prompt/输出 token 溢出 */
    public static final int MAX_SEG_CHARS = 1200;
    /** 切点搜索下限：句边界太靠前就退次级标点/硬切，避免切出碎块 */
    private static final int MIN_CUT = MAX_SEG_CHARS / 4;

    private TextSplitter() {
    }

    /** 按默认上限切块。 */
    public static List<String> splitLong(String line) {
        return splitLong(line, MAX_SEG_CHARS);
    }

    /**
     * 超长行按句子边界切块。优先句末标点（'.' 仅当后跟空白/行尾，避开 3.14、U.S.），
     * 退而求次级停顿标点/空格，再退硬切；切点落在代理对高位时回退一位，不拆字符。
     */
    public static List<String> splitLong(String line, int maxSegChars) {
        int max = Math.max(16, maxSegChars);
        int minCut = max / 4;
        List<String> out = new ArrayList<>();
        int i = 0;
        while (line.length() - i > max) {
            int end = i + max;
            int cut = findCut(line, i, end, minCut, TextSplitter::isSentenceEnd);
            if (cut < 0) {
                cut = findCut(line, i, end, minCut, TextSplitter::isSoftBreak);
            }
            if (cut < 0) {
                cut = end - 1;
            }
            if (Character.isHighSurrogate(line.charAt(cut))) {
                cut--;
            }
            out.add(line.substring(i, cut + 1));
            i = cut + 1;
        }
        out.add(line.substring(i));
        return out;
    }

    /** 在 [from+minCut, end) 内从后往前找第一个满足条件的切点，找不到返回 -1。 */
    private static int findCut(String line, int from, int end, int minCut, CutPredicate pred) {
        for (int j = end - 1; j >= from + minCut; j--) {
            if (pred.test(line, j)) {
                return j;
            }
        }
        return -1;
    }

    private interface CutPredicate {
        boolean test(String line, int idx);
    }

    private static boolean isSentenceEnd(String line, int idx) {
        char c = line.charAt(idx);
        if ("。！？!?…；;".indexOf(c) >= 0) {
            return true;
        }
        // '.' 仅当后一字符是空白或行尾才算句末（避开小数点、缩写）
        return c == '.' && (idx + 1 >= line.length() || Character.isWhitespace(line.charAt(idx + 1)));
    }

    private static boolean isSoftBreak(String line, int idx) {
        char c = line.charAt(idx);
        return "，,、）)】》”\" ".indexOf(c) >= 0 || Character.isWhitespace(c);
    }
}
