package com.aifanyi.support;

import java.util.ArrayList;
import java.util.List;

/**
 * 使用手册切块器（传统 RAG 的第一步）。<b>纯函数，可穷尽单测。</b>
 *
 * <p>切块策略：按 markdown 标题（## / ###）分节——手册每节本就围绕一个主题写，
 * 天然是最好的语义边界；超长的节再按空行段落聚合切分，保证单块不超过
 * {@link #MAX_CHARS}（嵌入模型对超长文本会截断，检索质量反而下降）。
 *
 * <p>每块携带标题路径（如「全能AI翻译 · 术语可信度四档」）：
 * 嵌入时拼进文本提升检索命中，回答时作为「出处」展示给用户。
 */
public final class HelpChunker {

    /** 单块字符上限：多语向量模型的有效窗口有限，超长部分等于白嵌 */
    static final int MAX_CHARS = 600;

    /** 一个知识块：标题路径 + 正文。 */
    public record Chunk(String title, String body) {
        /** 喂给嵌入模型/LLM 的完整文本（带标题上下文）。 */
        public String text() {
            return "【" + title + "】\n" + body;
        }
    }

    private HelpChunker() {
    }

    public static List<Chunk> chunk(String markdown) {
        List<Chunk> out = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return out;
        }
        String h2 = null;
        String h3 = null;
        StringBuilder buf = new StringBuilder();
        for (String raw : markdown.replace("\r\n", "\n").split("\n", -1)) {
            String line = raw.strip();
            if (line.startsWith("### ")) {
                flush(out, h2, h3, buf);
                h3 = line.substring(4).strip();
            } else if (line.startsWith("## ")) {
                flush(out, h2, h3, buf);
                h2 = line.substring(3).strip();
                h3 = null;
            } else if (line.startsWith("# ")) {
                flush(out, h2, h3, buf);            // 文档主标题：只当分隔，不进块
            } else {
                buf.append(raw).append('\n');
            }
        }
        flush(out, h2, h3, buf);
        return out;
    }

    private static void flush(List<Chunk> out, String h2, String h3, StringBuilder buf) {
        String text = buf.toString().strip();
        buf.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        String title = h2 == null ? "总览" : (h3 == null ? h2 : h2 + " · " + h3);
        if (text.length() <= MAX_CHARS) {
            out.add(new Chunk(title, text));
            return;
        }
        // 超长节：按空行段落聚合，攒到上限就出一块（段落本身超限时单独成块，不硬切句子）
        StringBuilder piece = new StringBuilder();
        for (String para : text.split("\n\\s*\n")) {
            String p = para.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (piece.length() > 0 && piece.length() + p.length() > MAX_CHARS) {
                out.add(new Chunk(title, piece.toString().strip()));
                piece.setLength(0);
            }
            piece.append(p).append("\n\n");
        }
        if (piece.length() > 0) {
            out.add(new Chunk(title, piece.toString().strip()));
        }
    }

    /**
     * 关键词打分（向量不可用时的检索兜底）：查询切成拉丁词 + 中日韩双字组合，
     * 数每个词元在块里出现几次，标题命中按 3 倍计。纯代码零依赖，结果确定。
     */
    public static int keywordScore(String question, Chunk c) {
        if (question == null || question.isBlank() || c == null) {
            return 0;
        }
        int score = 0;
        String title = c.title().toLowerCase(java.util.Locale.ROOT);
        String body = c.body().toLowerCase(java.util.Locale.ROOT);
        for (String token : tokenize(question)) {
            score += 3 * count(title, token) + count(body, token);
        }
        return score;
    }

    static List<String> tokenize(String q) {
        List<String> tokens = new ArrayList<>();
        String s = q.toLowerCase(java.util.Locale.ROOT);
        StringBuilder latin = new StringBuilder();
        char prevCjk = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetterOrDigit(ch) && ch < 0x2E80) {
                latin.append(ch);
                prevCjk = 0;
                continue;
            }
            if (latin.length() > 1) {
                tokens.add(latin.toString());
            }
            latin.setLength(0);
            if (ch >= 0x2E80) {                     // 中日韩：出双字组合
                if (prevCjk != 0) {
                    tokens.add("" + prevCjk + ch);
                }
                prevCjk = ch;
            } else {
                prevCjk = 0;
            }
        }
        if (latin.length() > 1) {
            tokens.add(latin.toString());
        }
        return tokens;
    }

    private static int count(String text, String token) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(token, i)) >= 0) {
            n++;
            i += token.length();
        }
        return n;
    }
}
