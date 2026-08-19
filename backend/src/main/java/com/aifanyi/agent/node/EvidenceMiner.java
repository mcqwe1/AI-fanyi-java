package com.aifanyi.agent.node;

import com.aifanyi.agent.model.TermDraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ④ 上下文证据挖掘：从已有文本里找术语的「定义句」。
 * <p><b>纯正则，零 token，约 1ms</b>——架构图把它画成 Agent 的一个步骤，但它本质是
 * 在已经拿到的文本上做模式匹配，没有任何理由花一次 LLM 往返。这也让它在
 * LLM 不可用、搜索不可用时依然有效，是降级路径上最后一块能提供真实依据的砖。
 * <p>典型收获：「CRISPR 是一种基因编辑技术」→ 告诉模型这是技术名而非人名，
 * 极大提升无搜索情形下的定译质量。
 */
public final class EvidenceMiner {

    /** 每个术语最多挖几句 */
    private static final int MAX_PER_TERM = 2;
    /** 定义句最长截取（超长的取前段，够模型判断即可） */
    private static final int MAX_SENTENCE = 200;
    /** 中日韩字符：出现次数统计据此决定走词边界正则还是子串计数 */
    private static final Pattern CJK =
            Pattern.compile("[\\u4e00-\\u9fa5\\u3040-\\u30ff\\uac00-\\ud7af]");

    /**
     * 定义句模板。{T} 处填术语（已转义）。
     * 覆盖中/英/日三语常见句式——源语言未知时全试一遍，反正零成本。
     */
    private static final List<String> PATTERNS = List.of(
            // 中文
            "{T}\\s*(?:是|就是|指的?是|即|乃)\\s*[^。！？\\n]{2,120}",
            "所谓\\s*{T}\\s*[，,]?\\s*[^。！？\\n]{2,120}",
            "{T}\\s*[（(][^）)]{2,80}[）)]",
            "{T}\\s*[，,]\\s*(?:也叫|又称|亦称|简称|全称)\\s*[^。！？\\n]{2,80}",
            // 英文
            "{T}\\s+(?:is|are|was|were)\\s+(?:a|an|the)?\\s*[^.!?\\n]{2,120}",
            "{T}\\s+(?:refers to|stands for|means)\\s+[^.!?\\n]{2,120}",
            "{T}\\s*[,(]\\s*(?:also known as|aka|short for)\\s*[^.)!?\\n]{2,80}",
            // 日文
            "{T}\\s*(?:とは|というのは)\\s*[^。！？\\n]{2,120}",
            "{T}\\s*(?:と(?:い|言)う)\\s*[^。！？\\n]{2,80}"
    );

    private EvidenceMiner() {
    }

    /**
     * 为每个术语挖定义句。
     *
     * @param text  全文（或摘要）
     * @param terms 待挖的术语
     * @return source → 定义句列表（没挖到的术语不出现在 map 里）
     */
    public static Map<String, List<String>> mine(String text, List<TermDraft> terms) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (text == null || text.isBlank() || terms == null || terms.isEmpty()) {
            return out;
        }
        for (TermDraft t : terms) {
            String src = t.source();
            if (src == null || src.isBlank()) {
                continue;
            }
            List<String> hits = mineOne(text, src);
            if (!hits.isEmpty()) {
                out.put(src, hits);
            }
        }
        return out;
    }

    /** 挖单个术语的定义句。 */
    public static List<String> mineOne(String text, String term) {
        List<String> hits = new ArrayList<>();
        String quoted = Pattern.quote(term);
        for (String tmpl : PATTERNS) {
            if (hits.size() >= MAX_PER_TERM) {
                break;
            }
            try {
                Pattern p = Pattern.compile(tmpl.replace("{T}", quoted),
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                Matcher m = p.matcher(text);
                while (m.find() && hits.size() < MAX_PER_TERM) {
                    String s = m.group().trim().replaceAll("\\s+", " ");
                    if (s.length() > MAX_SENTENCE) {
                        s = s.substring(0, MAX_SENTENCE) + "…";
                    }
                    if (!hits.contains(s)) {
                        hits.add(s);
                    }
                }
            } catch (Exception ignored) {
                // 单个模板编译/匹配失败不影响其余（术语含特殊字符时 quote 已兜住，此处双保险）
            }
        }
        return hits;
    }

    /**
     * 统计术语在全文的出现次数。
     * <p>这是「免费信号」：由代码在<b>全文</b>精确数出，不受喂给模型的摘要抽样影响，
     * 也不花一个 token。{@link TermTriage} 的规则 3（复合术语反复出现）直接建在这个数上。
     *
     * <p><b>必须卡词边界</b>（2026-08 修）：旧版直接 indexOf 数子串，
     * 数 "API" 会把 r<b>api</b>d、c<b>api</b>tal 算进去，数 "AI" 会把
     * ag<b>ai</b>n、s<b>ai</b>d、tr<b>ai</b>ning、m<b>ai</b>ntain 全算进去——
     * 英文素材里短词的次数是虚高的，直接污染收录判定。
     * <p>中日韩没有词间空格，词边界概念不适用（也不能用 {@code \b}，
     * 它在 CJK 与标点之间的行为并非我们想要的），继续走子串计数。
     */
    public static int countOccurrences(String fullText, String term) {
        if (fullText == null || term == null || term.isBlank()) {
            return 0;
        }
        String needle = term.trim();
        if (CJK.matcher(needle).find()) {
            return countSubstring(fullText, needle);
        }
        try {
            // 用 lookaround 而非 \b：术语可能以非单词字符开头/结尾（.NET、C++），\b 在那里会失效
            Pattern p = Pattern.compile(
                    "(?<![A-Za-z0-9])" + Pattern.quote(needle) + "(?![A-Za-z0-9])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            Matcher m = p.matcher(fullText);
            int n = 0;
            while (m.find()) {
                n++;
            }
            return n;
        } catch (Exception e) {
            return countSubstring(fullText, needle);     // 正则兜不住时退回子串，宁可虚高也别报 0
        }
    }

    /** 大小写不敏感的子串计数（CJK 路径与正则失败的兜底）。 */
    private static int countSubstring(String fullText, String term) {
        int n = 0;
        int i = 0;
        String hay = fullText.toLowerCase(java.util.Locale.ROOT);
        String needle = term.toLowerCase(java.util.Locale.ROOT);
        while ((i = hay.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
