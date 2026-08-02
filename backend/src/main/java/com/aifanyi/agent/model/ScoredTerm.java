package com.aifanyi.agent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * ⑤ 仲裁后的术语：候选 + 跨 Agent 一致性统计 + 代码算出的置信度。
 *
 * @param source        原文（保留最优候选的原始形态，不是规范化形式）
 * @param sourceNorm    规范化形式（去重与查重的唯一依据，绝不用于展示）
 * @param target        终译
 * @param strategy      译法策略
 * @param evidence      定译依据
 * @param authorityUrl  权威来源链接
 * @param reason        理由
 * @param selfReport    模型自报置信（仅作算分的一个特征）
 * @param occurrences   全文出现次数
 * @param proposals     提出该译法的候选总数（跨档案）
 * @param agreements    与最终译法一致的候选数
 * @param conflicts     冲突的其他译法（同源词不同译）
 * @param profileCode   来源档案
 * @param degraded      来自降级（超时/部分结果）的子 Agent
 * @param confidence    最终置信度 0~1（由 ConfidenceScorer 填充）
 * @param relatedIds    向量召回发现的疑似同词族条目（不自动合并，仅提示）
 */
public record ScoredTerm(String source, String sourceNorm, String target, Strategy strategy,
                         String evidence, String authorityUrl, String reason, String selfReport,
                         int occurrences, int proposals, int agreements, List<String> conflicts,
                         String profileCode, boolean degraded, double confidence,
                         List<Long> relatedIds) {

    public ScoredTerm withConfidence(double c) {
        return new ScoredTerm(source, sourceNorm, target, strategy, evidence, authorityUrl, reason,
                selfReport, occurrences, proposals, agreements, conflicts, profileCode, degraded,
                c, relatedIds);
    }

    public ScoredTerm withRelated(List<Long> ids) {
        return new ScoredTerm(source, sourceNorm, target, strategy, evidence, authorityUrl, reason,
                selfReport, occurrences, proposals, agreements, conflicts, profileCode, degraded,
                confidence, ids == null ? List.of() : ids);
    }

    public boolean hasAuthority() {
        return strategy == Strategy.AUTHORITATIVE
                || (authorityUrl != null && !authorityUrl.isBlank());
    }

    public boolean hasEvidence() {
        return (evidence != null && !evidence.isBlank()) || hasAuthority();
    }

    public boolean hasConflict() {
        return conflicts != null && !conflicts.isEmpty();
    }

    /** 是否做过搜索（决定「权威命中」特征是否参与算分）。无搜索时该特征缺失而非 0 分。 */
    public boolean searched() {
        return authorityUrl != null;   // 搜过但没命中权威时为空串，没搜过为 null
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
                    profileCode, degraded, 0, List.of());
        }
    }
}
