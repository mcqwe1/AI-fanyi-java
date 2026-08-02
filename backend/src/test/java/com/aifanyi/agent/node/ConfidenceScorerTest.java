package com.aifanyi.agent.node;

import com.aifanyi.agent.model.ScoredTerm;
import com.aifanyi.agent.model.Strategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfidenceScorer 单测。
 * <p>这是最需要穷尽测试的一块：算错了就是把 0.4 分的猜测写进用户的术语库，
 * 而且用户很难发现。重点验证「证据档位天花板」确实拦住了架构图的反向缺陷。
 */
class ConfidenceScorerTest {

    private static ScoredTerm.Builder base() {
        return ScoredTerm.builder()
                .source("Kubernetes").sourceNorm("kubernetes").target("Kubernetes")
                .selfReport("medium").occurrences(1).proposals(1).agreements(1)
                .profileCode("it");
    }

    // ─────────── 核心：证据档位天花板（修架构反向缺陷）───────────

    /**
     * <b>最关键的一条</b>：无搜索 + 单一提议 + 自报 high + 结构良好。
     * 按架构图的裸重归一会得 0.925 → 自动 verified 入库；
     * 天花板必须把它压到 0.59 以下（至多 ephemeral，不落库）。
     */
    @Test
    void singleUnverifiedGuessNeverReachesVerified() {
        ScoredTerm t = base().selfReport("high").source("API").target("应用程序接口").build();
        double s = ConfidenceScorer.score(t);
        assertTrue(s <= ConfidenceScorer.CEIL_SINGLE_GUESS,
                "单模型无据猜测不得超过 0.59，实际 " + s);
        assertTrue(s < 0.85, "绝不能自动进 verified");
    }

    /** 自报 high 但无任何证据 → 自报特征本身先降档。 */
    @Test
    void highSelfReportWithoutEvidenceIsDowngraded() {
        ScoredTerm high = base().selfReport("high").build();
        ScoredTerm medium = base().selfReport("medium").build();
        assertEquals(ConfidenceScorer.score(medium), ConfidenceScorer.score(high), 1e-9,
                "无证据时 high 应被强制降到 medium 档");
    }

    /** 多档案独立一致 → 至多 pending（0.82），仍不能自动 verified。 */
    @Test
    void multiAgentAgreementCapsAtPending() {
        ScoredTerm t = base().selfReport("high").proposals(3).agreements(3).occurrences(5).build();
        double s = ConfidenceScorer.score(t);
        assertTrue(s <= ConfidenceScorer.CEIL_MULTI_AGREE,
                "多方一致但无权威来源，上限 0.82，实际 " + s);
        assertTrue(s > ConfidenceScorer.CEIL_SINGLE_GUESS,
                "但应明显高于单方猜测");
    }

    /** 有权威来源且译文出现在证据里 → 可以进 verified 档。 */
    @Test
    void authoritativeHitCanReachVerified() {
        ScoredTerm t = base().selfReport("high")
                .strategy(Strategy.AUTHORITATIVE)
                .authorityUrl("https://kubernetes.io/zh/")
                .evidence("Kubernetes 官方中文站使用 Kubernetes 原名")
                .proposals(2).agreements(2).occurrences(6).build();
        double s = ConfidenceScorer.score(t);
        assertTrue(s >= 0.85, "权威命中应能达到 verified 门槛，实际 " + s);
        assertTrue(s <= ConfidenceScorer.CEIL_AUTHORITY);
    }

    /** 只命中权威域名但证据里没有该译法 → 只给半分，压不到 verified。 */
    @Test
    void authorityDomainWithoutTargetInEvidenceScoresLower() {
        ScoredTerm withTarget = base().selfReport("high").strategy(Strategy.AUTHORITATIVE)
                .authorityUrl("https://x.org/").evidence("官方译名是 容器编排").target("容器编排")
                .proposals(2).agreements(2).build();
        ScoredTerm without = base().selfReport("high").strategy(Strategy.AUTHORITATIVE)
                .authorityUrl("https://x.org/").evidence("这是一篇无关的介绍").target("容器编排")
                .proposals(2).agreements(2).build();
        assertTrue(ConfidenceScorer.score(withTarget) > ConfidenceScorer.score(without),
                "证据里真的含该译法，分数应更高");
    }

    // ─────────── 缺项重归一 ───────────

    /** 没搜索时权威特征缺失，不该按 0 分计入（那是惩罚，不是缺失）。 */
    @Test
    void missingAuthorityFeatureIsRenormalizedNotZeroed() {
        ScoredTerm notSearched = base().selfReport("medium").build();          // authorityUrl=null
        ScoredTerm searchedNoHit = base().selfReport("medium").authorityUrl("").build();
        assertTrue(ConfidenceScorer.score(notSearched) > ConfidenceScorer.score(searchedNoHit),
                "没搜过 应高于 搜了但没搜到（后者是真的 0 分）");
    }

    /** 冲突会扣一致性分。 */
    @Test
    void conflictReducesAgreement() {
        ScoredTerm clean = base().proposals(3).agreements(3).build();
        ScoredTerm conflicted = base().proposals(3).agreements(2)
                .conflicts(List.of("库伯内特斯")).build();
        assertTrue(ConfidenceScorer.score(clean) > ConfidenceScorer.score(conflicted));
    }

    /** 单一提议但高频出现：算弱一致性信号，略高于低频。 */
    @Test
    void highOccurrenceGivesWeakConsistencySignal() {
        ScoredTerm rare = base().occurrences(1).build();
        ScoredTerm frequent = base().occurrences(8).build();
        assertTrue(ConfidenceScorer.score(frequent) > ConfidenceScorer.score(rare),
                "高频出现是免费的弱一致性信号");
    }

    // ─────────── 降级打折 ───────────

    /** 来自超时子 Agent 的部分结果整体打折——可用于本次，但不该晋升为持久状态。 */
    @Test
    void degradedResultsAreDiscounted() {
        ScoredTerm normal = base().selfReport("high").strategy(Strategy.AUTHORITATIVE)
                .authorityUrl("https://x.org/").evidence("Kubernetes 官方")
                .proposals(2).agreements(2).build();
        ScoredTerm degraded = base().selfReport("high").strategy(Strategy.AUTHORITATIVE)
                .authorityUrl("https://x.org/").evidence("Kubernetes 官方")
                .proposals(2).agreements(2).degraded(true).build();
        assertEquals(ConfidenceScorer.score(normal) * ConfidenceScorer.DEGRADED_FACTOR,
                ConfidenceScorer.score(degraded), 1e-9);
    }

    // ─────────── 结构先验 ───────────

    @Test
    void structurePriorRecognizesAbbreviations() {
        assertTrue(ConfidenceScorer.structureScore("API") >= 0.9);
        assertTrue(ConfidenceScorer.structureScore("CRISPR") >= 0.9);
    }

    @Test
    void structurePriorRecognizesCamelCase() {
        assertTrue(ConfidenceScorer.structureScore("JavaScript") >= 0.85);
        assertTrue(ConfidenceScorer.structureScore("PostgreSQL") >= 0.8);
    }

    @Test
    void structurePriorRecognizesAlnumMix() {
        assertTrue(ConfidenceScorer.structureScore("GPT-4") >= 0.75);
        assertTrue(ConfidenceScorer.structureScore("H2O") >= 0.75);
    }

    @Test
    void structurePriorHandlesCjkAndPlainWords() {
        assertTrue(ConfidenceScorer.structureScore("阿尔茨海默病") > 0);
        assertTrue(ConfidenceScorer.structureScore("hospital") > 0);
        assertEquals(0, ConfidenceScorer.structureScore(""));
        assertEquals(0, ConfidenceScorer.structureScore(null));
    }

    // ─────────── 边界 ───────────

    @Test
    void scoreAlwaysInRange() {
        for (String sr : new String[]{"high", "medium", "low", "garbage", null}) {
            for (int occ : new int[]{0, 1, 100}) {
                for (int prop : new int[]{1, 5}) {
                    double s = ConfidenceScorer.score(base().selfReport(sr)
                            .occurrences(occ).proposals(prop).agreements(prop).build());
                    assertTrue(s >= 0 && s <= 1, "分数越界: " + s);
                }
            }
        }
    }

    /** 天花板判定本身的三档。 */
    @Test
    void ceilingTiers() {
        assertEquals(ConfidenceScorer.CEIL_AUTHORITY,
                ConfidenceScorer.ceiling(base().strategy(Strategy.AUTHORITATIVE).build()));
        assertEquals(ConfidenceScorer.CEIL_MULTI_AGREE,
                ConfidenceScorer.ceiling(base().agreements(2).build()));
        assertEquals(ConfidenceScorer.CEIL_SINGLE_GUESS,
                ConfidenceScorer.ceiling(base().agreements(1).build()));
    }
}
