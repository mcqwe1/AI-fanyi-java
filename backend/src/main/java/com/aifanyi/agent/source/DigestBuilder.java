package com.aifanyi.agent.source;

import com.aifanyi.agent.model.Chunk;
import com.aifanyi.agent.model.SourceDoc;

import java.util.List;

/**
 * 分层采样摘要（harness 上下文管理层）。
 * <p><b>为什么不把全文喂给每个子 Agent</b>：N 个子 Agent × 全文 = N 倍 token 成本，
 * 且长视频必然撑爆 30 秒预算（GeminiClient 注释记录的超线性病理正源于此）。
 * <p>安全性来自职责拆分：摘要只负责「发现有哪些候选术语」，而「术语出现多少次」
 * 由 {@link com.aifanyi.agent.node.EvidenceMiner#countOccurrences} 在<b>全文</b>精确统计。
 * 采样丢掉的重复出现不会影响一致性打分。
 * <p>采样策略：头部（开场交代主题）+ 均匀分布的中段窗口 + 尾部（总结），按行边界切。
 */
public final class DigestBuilder {

    /** 头部占比：开场往往交代主题与主要专名 */
    private static final double HEAD_RATIO = 0.35;
    /** 尾部占比：结尾常有总结与致谢名单 */
    private static final double TAIL_RATIO = 0.15;
    /** 中段取几个窗口 */
    private static final int MIDDLE_WINDOWS = 4;

    private DigestBuilder() {
    }

    /** 全文不超限就原样返回；否则分层采样。 */
    public static String build(SourceDoc doc, int maxChars) {
        return build(doc.chunks(), maxChars);
    }

    public static String build(List<Chunk> chunks, int maxChars) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        StringBuilder all = new StringBuilder();
        for (Chunk c : chunks) {
            if (all.length() > 0) {
                all.append('\n');
            }
            all.append(c.text());
        }
        if (all.length() <= maxChars) {
            return all.toString();
        }

        int headChars = (int) (maxChars * HEAD_RATIO);
        int tailChars = (int) (maxChars * TAIL_RATIO);
        int midTotal = maxChars - headChars - tailChars;
        int perWindow = Math.max(200, midTotal / MIDDLE_WINDOWS);

        StringBuilder sb = new StringBuilder(maxChars + 64);
        sb.append(takeLines(chunks, 0, headChars, true));

        // 中段：跳过头尾覆盖区，均匀取窗口
        int total = chunks.size();
        int headEnd = lineIndexAfterChars(chunks, headChars);
        int tailStart = total - lineCountForChars(chunks, tailChars);
        int span = Math.max(1, tailStart - headEnd);
        for (int w = 0; w < MIDDLE_WINDOWS; w++) {
            int from = headEnd + (int) ((long) span * w / MIDDLE_WINDOWS);
            if (from >= tailStart) {
                break;
            }
            String piece = takeLines(chunks, from, perWindow, true);
            if (!piece.isBlank()) {
                sb.append("\n…\n").append(piece);
            }
        }
        String tail = takeLines(chunks, Math.max(0, tailStart), tailChars, true);
        if (!tail.isBlank()) {
            sb.append("\n…\n").append(tail);
        }
        return sb.toString();
    }

    /** 从第 from 行起取够 maxChars 的完整行。 */
    private static String takeLines(List<Chunk> chunks, int from, int maxChars, boolean stopAtLimit) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < chunks.size(); i++) {
            String line = chunks.get(i).text();
            if (line == null) {
                continue;
            }
            if (stopAtLimit && sb.length() + line.length() + 1 > maxChars && sb.length() > 0) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
            if (sb.length() >= maxChars) {
                break;
            }
        }
        return sb.toString();
    }

    /** 累计到 chars 字符时的行下标。 */
    private static int lineIndexAfterChars(List<Chunk> chunks, int chars) {
        int acc = 0;
        for (int i = 0; i < chunks.size(); i++) {
            acc += chunks.get(i).text() == null ? 0 : chunks.get(i).text().length() + 1;
            if (acc >= chars) {
                return i + 1;
            }
        }
        return chunks.size();
    }

    /** 从尾部倒推 chars 字符需要多少行。 */
    private static int lineCountForChars(List<Chunk> chunks, int chars) {
        int acc = 0;
        int n = 0;
        for (int i = chunks.size() - 1; i >= 0; i--) {
            acc += chunks.get(i).text() == null ? 0 : chunks.get(i).text().length() + 1;
            n++;
            if (acc >= chars) {
                break;
            }
        }
        return n;
    }
}
