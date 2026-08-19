package com.aifanyi.llm;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 译文对齐守卫：判断模型还回来的译文，是不是真的落在它自己声称的那一行上。
 *
 * <p><b>为什么需要这一层</b>——批量翻译把 N 行原文编号发给模型，让它按行号把译文还回来。
 * 旧实现对这个行号<b>完全信任</b>：模型说第 7 行，就填第 7 行。但模型并不总是老实：
 * 当 ASR 把长句从中间劈开（一行结尾是 “and the sweet”，下一行才接 “potato that's a sub genre”），
 * 模型看到半截话会忍不住<b>自行重新断句</b>，把相邻两行并成一句翻译，于是整批译文从某处开始
 * 整体串位；为了凑够行数，它再把最后一条译文重复几遍填满。代码看不出任何异常，照单全收。
 *
 * <p>2026-08 实测（61.7 分钟英语视频，40 行一批）：575 条字幕里 <b>130 条（22.6%）</b>译文串了
 * 1~4 行，分布在 12 个批次；12 段错位<b>全部困在批内</b>、每批开头重新对齐，批尾出现
 * 连续重复译文（批内第 39、40 行）——正是上面这套「串位 + 凑数填充」的签名。
 *
 * <p>本类提供两道校验，供 {@link OpenAiTranslator#applyTranslations} 使用：
 * <ol>
 *   <li><b>锚点校验</b>（主）——模型每条译文须附带它所翻那行原文的开头几个词，逐条核对
 *       是否落在声称的行上。对不上就当这行没译，交给既有的补翻重发。</li>
 *   <li><b>启发式兜底</b>（备）——模型压根不给锚点时才启用，靠「批尾重复译文」和
 *       「原文/译文长度的滑窗相关性」两个信号事后判定，不依赖模型配合。</li>
 * </ol>
 *
 * <p>已知取舍（有意为之）：
 * <ul>
 *   <li>锚点校验只在<b>有正面证据说明还错了</b>时才判失败：模型只抄开头两个词属于合法短抄，
 *       不算失配；但锚点长过它声称的那行原文就是失配（抄的是跨行整句）。相邻两行开头恰好雷同
 *       且长度也相近时，单行偏移仍可能判为匹配；但串位总是连续成片发生，同片的其余行会失配
 *       并触发重发，且开头雷同的两行译文本身也相近。</li>
 *   <li>启发式只对<b>≥{@value #MIN_LINES_FOR_STATS} 行</b>的请求启用，且要求可疑区连续
 *       ≥{@value #MIN_RUN} 行才算数：样本太少时长度相关性本身就是噪声，宁可漏判也不误伤
 *       （误判的代价是白白重发一批）。</li>
 * </ul>
 */
@Slf4j
public final class AlignmentGuard {

    private AlignmentGuard() {
    }

    /** 锚点最多比较多少个归一化字符；短于此长度的行按可比部分的全长比较。 */
    static final int ANCHOR_CHARS = 16;

    /** 允许锚点比原文长出的字符余量（给模型多抄一两个字符留的抖动空间）。 */
    static final int LEN_SLACK = 4;

    /** 少于这么多行就不跑长度统计：样本太小，相关性没有意义。 */
    static final int MIN_LINES_FOR_STATS = 16;

    /** 长度统计的滑动窗口宽度。 */
    private static final int WINDOW = 12;

    /** 最多往后找几行的偏移（实测错位量级为 1~4）。 */
    private static final int MAX_OFFSET = 4;

    /** 可疑区至少要连续这么多行才上报，压掉零星噪声。 */
    static final int MIN_RUN = 3;

    /** 非 0 偏移要比 0 偏移的相关性高出这么多，才认定确实错位。 */
    private static final double MARGIN = 0.15;

    /** 归一化：转小写、只保留字母/数字/汉字。模型抄锚点时的空格与标点差异不该算失配。 */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 锚点是否落在这行原文上。
     *
     * <p>两道判断：
     * <ol>
     *   <li><b>前缀一致</b>——比较长度取三者最小值：锚点长度、原文长度、{@value #ANCHOR_CHARS}。
     *       模型只抄了开头两个词、而原文很长，属于合法的短抄，不算失配。</li>
     *   <li><b>锚点不得比原文长</b>（留 {@value #LEN_SLACK} 字符余量）——模型抄的是「该行开头」，
     *       正常不可能超出该行全长。一旦超出，说明它抄的是<b>跨行拼起来的整句</b>，
     *       而这正是错位的成因。这道判断专治「短行被长行的同款开头蒙混过关」：
     *       原文只有 “you know”，锚点却是 “you know what i mean”，前缀比得上，但长度露了馅。</li>
     * </ol>
     * 余量只是给「模型多抄了一两个字符」留的抖动空间；真误杀了也只是多重发这一行，
     * 比放过一片错位便宜得多。
     *
     * @param anchor 模型回传的原文开头片段；空白视为没给锚点，返回 false 由调用方另行处置
     */
    public static boolean anchorMatches(String anchor, String source) {
        String a = normalize(anchor);
        String s = normalize(source);
        if (a.isEmpty() || s.isEmpty()) {
            return false;
        }
        int k = Math.min(ANCHOR_CHARS, Math.min(a.length(), s.length()));
        if (!a.regionMatches(0, s, 0, k)) {
            return false;
        }
        return a.length() <= s.length() + LEN_SLACK;
    }

    /** 锚点是否落在本次请求的<b>任意</b>一行上（用于分辨「模型串位了」还是「模型压根没抄原文」）。 */
    public static boolean anchorMatchesAny(String anchor, List<String> sources) {
        for (String src : sources) {
            if (anchorMatches(anchor, src)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 无锚点时的兜底：找出疑似错位的行号（本次请求内的下标）。
     *
     * @param sources  本次请求的原文行
     * @param proposed 模型给出的译文，与 sources 等长；未返回的行为 null
     * @return 疑似错位的下标；空集表示没发现问题
     */
    public static Set<Integer> suspectDrift(List<String> sources, String[] proposed) {
        Set<Integer> bad = new LinkedHashSet<>();
        int n = Math.min(sources.size(), proposed.length);
        if (n == 0) {
            return bad;
        }
        markDuplicateRuns(sources, proposed, n, bad);
        markLengthDrift(sources, proposed, n, bad);
        return bad;
    }

    /**
     * ① 凑数填充：相邻两行译文一模一样，而原文并不相同。
     * <p>模型串位后为了把行数补齐，会把最后一条译文重复几遍——实测两处错位的批尾都是这个样子。
     * 原文本来就重复的（口头禅连说两遍）不算。
     */
    private static void markDuplicateRuns(List<String> sources, String[] proposed, int n, Set<Integer> bad) {
        for (int i = 1; i < n; i++) {
            String prev = proposed[i - 1];
            String cur = proposed[i];
            if (prev == null || cur == null || prev.isBlank() || !prev.equals(cur)) {
                continue;
            }
            if (normalize(sources.get(i - 1)).equals(normalize(sources.get(i)))) {
                continue;               // 原文就是重复的，译文相同天经地义
            }
            bad.add(i - 1);
            bad.add(i);
        }
    }

    /**
     * ② 长度错位：同一句话的原文与译文长度大体成比例，逐行对齐时相关性在偏移 0 处最高。
     * <p>若某个窗口在偏移 k&gt;0 处明显更高，说明这一段的译文其实对应的是后面第 k 行的原文。
     * 这正是排查本次故障时用的方法（滑窗求最佳偏移），此处作为无锚点时的兜底。
     */
    private static void markLengthDrift(List<String> sources, String[] proposed, int n, Set<Integer> bad) {
        if (n < MIN_LINES_FOR_STATS) {
            return;                     // 样本太少，相关性是噪声
        }
        double[] src = new double[n];
        double[] tgt = new double[n];
        for (int i = 0; i < n; i++) {
            src[i] = normalize(sources.get(i)).length();
            tgt[i] = proposed[i] == null ? 0 : normalize(proposed[i]).length();
        }
        boolean[] drifted = new boolean[n];
        for (int i = 0; i < n; i++) {
            int lo = Math.max(0, Math.min(i - WINDOW / 2, n - WINDOW));
            int hi = Math.min(n, lo + WINDOW);
            double base = corr(tgt, lo, src, lo, hi - lo);
            double best = base;
            int bestOff = 0;
            for (int k = 1; k <= MAX_OFFSET; k++) {
                if (lo + k + (hi - lo) > n) {
                    break;
                }
                double c = corr(tgt, lo, src, lo + k, hi - lo);
                if (c > best + MARGIN) {
                    best = c;
                    bestOff = k;
                }
            }
            drifted[i] = bestOff != 0;
        }
        // 只上报连续 >= MIN_RUN 行的可疑区，压掉零星噪声
        int runStart = -1;
        for (int i = 0; i <= n; i++) {
            boolean on = i < n && drifted[i];
            if (on && runStart < 0) {
                runStart = i;
            } else if (!on && runStart >= 0) {
                if (i - runStart >= MIN_RUN) {
                    for (int j = runStart; j < i; j++) {
                        bad.add(j);
                    }
                }
                runStart = -1;
            }
        }
    }

    /** 皮尔逊相关系数；任一侧方差为 0（长度全相同）时返回 -2，表示「无从判断」。 */
    private static double corr(double[] a, int aFrom, double[] b, int bFrom, int len) {
        if (len < 3) {
            return -2;
        }
        double ma = 0;
        double mb = 0;
        for (int i = 0; i < len; i++) {
            ma += a[aFrom + i];
            mb += b[bFrom + i];
        }
        ma /= len;
        mb /= len;
        double cov = 0;
        double va = 0;
        double vb = 0;
        for (int i = 0; i < len; i++) {
            double da = a[aFrom + i] - ma;
            double db = b[bFrom + i] - mb;
            cov += da * db;
            va += da * da;
            vb += db * db;
        }
        if (va <= 0 || vb <= 0) {
            return -2;
        }
        return cov / (Math.sqrt(va) * Math.sqrt(vb));
    }

    /** 把下标集合排序成便于打日志的列表。 */
    public static List<Integer> sorted(Set<Integer> s) {
        List<Integer> out = new ArrayList<>(s);
        java.util.Collections.sort(out);
        return out;
    }
}
