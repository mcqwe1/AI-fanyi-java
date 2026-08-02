package com.aifanyi.agent.node;

import com.aifanyi.agent.model.ScoredTerm;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ⑤ 置信度算分（harness 评估与观测层）。<b>纯函数，无依赖，可穷尽单测。</b>
 *
 * <p>核心原则：<b>模型自报只是一个特征，不是结论</b>。模型说 high 而拿不出证据的，
 * 强制降到 medium 档——自信不等于正确。
 *
 * <p>四个特征按架构给的权重加权，缺项时权重重新归一（不给缺项 0 分）：
 * <pre>
 *   0.25 · LLM 自报三档
 *   0.35 · 跨 Agent 一致性（免费信号：出现次数 + 多档案独立同意率）
 *   0.25 · 权威来源命中（没搜索时该特征缺失）
 *   0.15 · 结构先验（缩写/驼峰/复合词等正则特征）
 * </pre>
 *
 * <p><b>修正架构图的一个反向缺陷</b>：单纯「缺项重归一」会让证据越少分越高——
 * 没搜索、单一提议时 present 权重仅 0.40，自报 high + 结构良好即得
 * (0.25×1.0 + 0.15×0.8) / 0.40 = 0.925 ≥ 0.85，<b>自动 verified 入用户术语库</b>，
 * 全凭一个模型的无据猜测。故归一后再套<b>证据档位天花板</b>：
 * <pre>
 *   有权威来源      → 上限 0.95（可 verified）
 *   多档案独立一致   → 上限 0.82（至多 pending，需人工确认）
 *   单模型猜测      → 上限 0.59（至多 ephemeral，绝不落库）
 * </pre>
 */
public final class ConfidenceScorer {

    // ---- 权重（与架构图一致）----
    private static final double W_SELF = 0.25;
    private static final double W_AGREE = 0.35;
    private static final double W_AUTHORITY = 0.25;
    private static final double W_STRUCTURE = 0.15;

    // ---- 证据档位天花板 ----
    static final double CEIL_AUTHORITY = 0.95;
    static final double CEIL_MULTI_AGREE = 0.82;
    static final double CEIL_SINGLE_GUESS = 0.59;
    /** 来自降级（超时/部分结果）子 Agent 的候选整体打折：可用于本次一致性，但不该晋升为持久状态 */
    static final double DEGRADED_FACTOR = 0.85;

    // ---- 结构先验正则 ----
    private static final Pattern ALL_CAPS = Pattern.compile("^[A-Z][A-Z0-9]{1,9}$");
    /** 驼峰：允许尾部或中部出现连续大写段（PostgreSQL、GraphQL、OpenAI 都是这形态） */
    private static final Pattern CAMEL = Pattern.compile("^[A-Z][a-z]+(?:[A-Z]+[a-z]*)+$");
    private static final Pattern HYPHEN = Pattern.compile("^[A-Za-z]+(?:-[A-Za-z0-9]+)+$");
    private static final Pattern ALNUM_MIX = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z0-9.-]{2,20}$");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fa5\\u3040-\\u30ff]");

    private ConfidenceScorer() {
    }

    /** 算分。返回 0~1。 */
    public static double score(ScoredTerm t) {
        double weighted = 0;
        double present = 0;

        // ① LLM 自报（永远存在）
        double self = selfScore(t.selfReport());
        // 自报 high 却拿不出任何证据 → 强制降到 medium 档（自信 ≠ 正确）
        if (self >= 1.0 && !t.hasEvidence()) {
            self = 0.6;
        }
        weighted += W_SELF * self;
        present += W_SELF;

        // ② 跨 Agent 一致性（只有多个候选时该特征才有意义）
        if (t.proposals() > 1) {
            double agree = (double) t.agreements() / t.proposals();
            // 冲突扣分：同源词多译法说明各 Agent 看法不一
            if (t.hasConflict()) {
                agree *= 0.7;
            }
            weighted += W_AGREE * clamp(agree);
            present += W_AGREE;
        } else if (t.occurrences() >= 3) {
            // 单一提议但高频出现：出现次数本身是弱一致性信号（免费，代码统计）
            double freq = Math.min(1.0, t.occurrences() / 8.0);
            weighted += W_AGREE * freq * 0.5;     // 折半计入，弱于真正的多方一致
            present += W_AGREE;
        }

        // ③ 权威命中（没做过搜索时该特征缺失，而非 0 分）
        if (t.searched()) {
            double auth;
            if (t.hasAuthority()) {
                // 权威域名命中 + 译文确实出现在证据里 → 满分；仅域名命中 → 半分
                auth = evidenceContainsTarget(t) ? 1.0 : 0.5;
            } else {
                auth = 0;                          // 搜了但没搜到权威来源
            }
            weighted += W_AUTHORITY * auth;
            present += W_AUTHORITY;
        }

        // ④ 结构先验（永远存在）
        weighted += W_STRUCTURE * structureScore(t.source());
        present += W_STRUCTURE;

        double raw = present <= 0 ? 0 : weighted / present;
        return clamp(Math.min(raw, ceiling(t)) * (t.degraded() ? DEGRADED_FACTOR : 1.0));
    }

    /**
     * 证据档位天花板——防止「证据越少分越高」。
     * 这是本类相对架构图的关键修正，缺了它 0.4 分的猜测会混进用户术语库。
     */
    static double ceiling(ScoredTerm t) {
        if (t.hasAuthority()) {
            return CEIL_AUTHORITY;
        }
        if (t.agreements() >= 2) {
            return CEIL_MULTI_AGREE;
        }
        return CEIL_SINGLE_GUESS;
    }

    private static double selfScore(String report) {
        if (report == null) {
            return 0.5;
        }
        return switch (report.trim().toLowerCase(Locale.ROOT)) {
            case "high" -> 1.0;
            case "medium", "mid" -> 0.6;
            case "low" -> 0.3;
            default -> 0.5;
        };
    }

    /** 译文是否真的出现在证据片段里——只命中域名不代表这条证据支持该译法。 */
    private static boolean evidenceContainsTarget(ScoredTerm t) {
        if (t.evidence() == null || t.evidence().isBlank()
                || t.target() == null || t.target().isBlank()) {
            return false;
        }
        return t.evidence().toLowerCase(Locale.ROOT).contains(t.target().toLowerCase(Locale.ROOT));
    }

    /**
     * 结构先验：形态本身就说明「这是个专名、通用模型容易译错」的程度。
     * ALL-CAPS 缩写、驼峰、连字符复合词、字母数字混合都是强信号。
     */
    static double structureScore(String source) {
        if (source == null || source.isBlank()) {
            return 0;
        }
        String s = source.trim();
        double score = 0;
        if (ALL_CAPS.matcher(s).matches()) {
            score = Math.max(score, 0.9);          // API、CRISPR、HTTP
        }
        if (CAMEL.matcher(s).matches()) {
            score = Math.max(score, 0.85);         // JavaScript、PostgreSQL
        }
        if (HYPHEN.matcher(s).matches()) {
            score = Math.max(score, 0.75);         // sound-meaning、e-commerce
        }
        if (ALNUM_MIX.matcher(s).matches()) {
            score = Math.max(score, 0.8);          // GPT-4、H2O、iPhone15
        }
        if (CJK.matcher(s).find()) {
            // 源文含中日文字：结构先验用不上（不是拉丁形态），给中性分
            score = Math.max(score, 0.4);
        }
        if (score == 0) {
            // 普通单词/短语：长度略作加权，长复合词更可能是专名
            score = s.length() >= 12 ? 0.5 : 0.3;
        }
        return score;
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
