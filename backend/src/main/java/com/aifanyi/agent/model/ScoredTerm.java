package com.aifanyi.agent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * ⑤ 仲裁后的术语：候选 + 跨 Agent 一致性统计。
 *
 * <p><b>2026-08 重构：去掉了 confidence 标量分。</b>「存不存库、启不启用」现在由
 * {@link com.aifanyi.agent.node.TermTriage} 按规则直接判定，中间那个 0~1 的分数
 * 既无校准数据、又只用来做一个二值决策，留着只会让人误以为它有意义。
 * 前端从未引用过该字段（查证过：frontend/src 零命中），删除对界面零影响。
 *
 * @param source        原文（保留最优候选的原始形态，不是规范化形式）
 * @param sourceNorm    规范化形式（去重与查重的唯一依据，绝不用于展示）
 * @param target        终译
 * @param strategy      译法策略（分诊规则 1 的输入，步骤 A 与 C 都会产出）
 * @param evidence      定译依据
 * @param authorityUrl  权威来源链接：核验通过为 URL / 搜了没命中为 "" / 没搜过为 null
 * @param reason        模型给的一句话理由
 * @param selfReport    模型自报置信 high/medium/low（<b>仅留作 trace 观测</b>，不再参与任何判定
 *                      ——自报本就不可信，旧版还给了它 0.25 权重）
 * @param occurrences   全文出现次数（代码统计，非模型自报）
 * @param proposals     提出该译法的候选总数（跨档案）
 * @param agreements    与最终译法一致的候选数（分诊规则的 B 档依据）
 * @param conflicts     冲突的其他译法（同源词不同译）
 * @param profileCode   来源档案
 * @param degraded      来自降级（超时/部分结果）的子 Agent，仅供 trace 展示
 * @param relatedIds    向量召回发现的疑似同词族条目（分诊规则 4 的输入）
 */
public record ScoredTerm(String source, String sourceNorm, String target, Strategy strategy,
                         String evidence, String authorityUrl, String reason, String selfReport,
                         int occurrences, int proposals, int agreements, List<String> conflicts,
                         String profileCode, boolean degraded, List<Long> relatedIds) {

    public ScoredTerm withRelated(List<Long> ids) {
        return new ScoredTerm(source, sourceNorm, target, strategy, evidence, authorityUrl, reason,
                selfReport, occurrences, proposals, agreements, conflicts, profileCode, degraded,
                ids == null ? List.of() : ids);
    }

    /**
     * 是否有<b>代码核验过</b>的权威来源（分诊 A 档的唯一依据）。
     *
     * <p>只认 authorityUrl，<b>不认 strategy==AUTHORITATIVE</b>：后者是模型自报的字段，
     * 而 authorityUrl 必须过 {@link com.aifanyi.agent.search.SearchConfig#verifyAuthorityUrl}
     * 的三重核验（出自实际检索结果 + 域名在白名单 + 该结果确实谈到这个词）。
     *
     * <p>2026-08 收紧：抽词阶段开始产出 strategy 之后，旧的「或」判定等于给模型开了后门——
     * 它可以在完全没联网的情况下自称 AUTHORITATIVE，直接拿到最高档并自动入库启用。
     * 没配搜索时本就不该有任何 A 档，这是诚实的结论，不是缺陷。
     */
    public boolean hasAuthority() {
        return authorityUrl != null && !authorityUrl.isBlank();
    }

    public boolean hasEvidence() {
        return (evidence != null && !evidence.isBlank()) || hasAuthority();
    }

    public boolean hasConflict() {
        return conflicts != null && !conflicts.isEmpty();
    }

    /** 是否做过搜索（搜了没命中权威为空串，没搜过为 null）。 */
    public boolean searched() {
        return authorityUrl != null;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 便于 Arbitrator 组装。 */
    public static final class Builder {
        private String source, sourceNorm, target, evidence, authorityUrl, reason, selfReport, profileCode;
        private Strategy strategy;
        private int occurrences, proposals = 1, agreements = 1;
        private List<String> conflicts = new ArrayList<>();
        private boolean degraded;

        public Builder source(String v) { this.source = v; return this; }
        public Builder sourceNorm(String v) { this.sourceNorm = v; return this; }
        public Builder target(String v) { this.target = v; return this; }
        public Builder strategy(Strategy v) { this.strategy = v; return this; }
        public Builder evidence(String v) { this.evidence = v; return this; }
        public Builder authorityUrl(String v) { this.authorityUrl = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }
        public Builder selfReport(String v) { this.selfReport = v; return this; }
        public Builder occurrences(int v) { this.occurrences = v; return this; }
        public Builder proposals(int v) { this.proposals = v; return this; }
        public Builder agreements(int v) { this.agreements = v; return this; }
        public Builder conflicts(List<String> v) { this.conflicts = v == null ? new ArrayList<>() : v; return this; }
        public Builder profileCode(String v) { this.profileCode = v; return this; }
        public Builder degraded(boolean v) { this.degraded = v; return this; }

        public ScoredTerm build() {
            return new ScoredTerm(source, sourceNorm, target, strategy, evidence, authorityUrl,
                    reason, selfReport, occurrences, proposals, agreements, List.copyOf(conflicts),
                    profileCode, degraded, List.of());
        }
    }
}
