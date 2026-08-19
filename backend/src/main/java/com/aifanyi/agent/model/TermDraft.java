package com.aifanyi.agent.model;

import java.util.List;

/**
 * 一条术语候选（子 Agent 的工作单元，在 DAG 各步骤间被逐步精炼）。
 * <p>不可变 record，精炼时用 withXxx 生成新实例——并发环境下省掉一整类可变状态 bug。
 *
 * @param source      原文术语
 * @param target      译法（步骤 A 是初步猜测，步骤 C 是终稿）
 * @param needSearch  是否需要联网核实（模型判定，代码据此挑选搜索目标）
 * @param selfReport  模型自报置信 high/medium/low（仅作 ⑤ 算分的一个特征，不直接采信）
 * @param queries     模型建议的搜索词（代码会截断到预算允许的条数）
 * @param strategy    终稿采用的策略
 * @param evidence    定译依据（权威来源片段或上下文定义句）
 * @param authorityUrl 权威来源 URL
 * @param reason      一句话理由
 * @param occurrences 全文出现次数（代码统计，非模型自报）
 * @param profileCode 产出该候选的档案
 */
public record TermDraft(String source, String target, boolean needSearch, String selfReport,
                        List<String> queries, Strategy strategy, String evidence,
                        String authorityUrl, String reason, int occurrences, String profileCode) {

    public static TermDraft of(String source, String target, boolean needSearch,
                               String selfReport, List<String> queries, String profileCode) {
        return new TermDraft(source, target, needSearch, selfReport,
                queries == null ? List.of() : queries, null, null, null, null, 0, profileCode);
    }

    public TermDraft withOccurrences(int n) {
        return new TermDraft(source, target, needSearch, selfReport, queries,
                strategy, evidence, authorityUrl, reason, n, profileCode);
    }

    /** 步骤 A 解析出的初步策略（步骤 C 若跑到会再覆盖一次）。 */
    public TermDraft withStrategy(Strategy s) {
        return new TermDraft(source, target, needSearch, selfReport, queries,
                s, evidence, authorityUrl, reason, occurrences, profileCode);
    }

    /** 步骤 C 的精炼结果覆盖上来（保留 A 步骤已算好的 occurrences）。 */
    public TermDraft resolved(String newTarget, Strategy st, String ev, String url, String rsn,
                              String newSelfReport) {
        return new TermDraft(source,
                newTarget == null || newTarget.isBlank() ? target : newTarget,
                needSearch,
                newSelfReport == null || newSelfReport.isBlank() ? selfReport : newSelfReport,
                // 步骤 C 没给策略就保留步骤 A 的：无脑覆盖会在模型漏字段时把已有信息抹成 null
                queries, st == null ? strategy : st, ev, url, rsn, occurrences, profileCode);
    }

    /**
     * 落定「权威来源」这一事实（由代码核验，不采信模型自报）。
     *
     * @param url 核验通过的 URL / 搜了没命中传 "" / <b>没搜过传 null</b>
     *            （⑤ 靠这个区分「特征缺失」与「特征为 0」，不可混用）
     * @param st  可能被降级后的策略
     */
    public TermDraft withVerifiedAuthority(String url, Strategy st) {
        return new TermDraft(source, target, needSearch, selfReport, queries,
                st, evidence, url, reason, occurrences, profileCode);
    }

    public boolean hasAuthority() {
        return strategy == Strategy.AUTHORITATIVE
                || (authorityUrl != null && !authorityUrl.isBlank());
    }

    public boolean valid() {
        return source != null && !source.isBlank() && target != null && !target.isBlank();
    }
}
