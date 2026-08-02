package com.aifanyi.agent.node;

import com.aifanyi.agent.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Arbitrator 与 TermStateMachine.classify 的纯函数单测。
 * 去重的确定性尤其重要——同一批输入每次跑出不同术语表会让用户彻底失去信任。
 */
class ArbitratorTest {

    private final Arbitrator arbitrator = new Arbitrator();

    private static TermDraft draft(String source, String target, String profile) {
        return new TermDraft(source, target, false, "medium", List.of(),
                null, null, null, null, 3, profile);
    }

    private static TermDraft draftAuth(String source, String target, String profile, String url) {
        return new TermDraft(source, target, false, "high", List.of(),
                Strategy.AUTHORITATIVE, "官方译名为 " + target, url, "权威", 3, profile);
    }

    private static SubAgentResult result(String profile, List<TermDraft> terms) {
        return new SubAgentResult(profile, SubAgentStatus.DONE, terms, Step.DONE, null, 100, 50, null);
    }

    // ─────────── 规范化去重 ───────────

    @Test
    void normalizesCaseAndPunctuation() {
        assertEquals(Arbitrator.norm("API"), Arbitrator.norm("api"));
        assertEquals(Arbitrator.norm("Kubernetes"), Arbitrator.norm(" kubernetes "));
        assertEquals(Arbitrator.norm("e-commerce"), Arbitrator.norm("E Commerce"));
        assertEquals("", Arbitrator.norm(null));
        assertEquals("", Arbitrator.norm("  "));
    }

    /** 全半角统一（NFKC）：ＡＰＩ 与 API 应视为同一词。 */
    @Test
    void normalizesFullWidth() {
        assertEquals(Arbitrator.norm("API"), Arbitrator.norm("ＡＰＩ"));
    }

    @Test
    void mergesApiAndApiIntoOne() {
        List<ScoredTerm> out = arbitrator.arbitrate(List.of(
                result("it", List.of(draft("API", "接口", "it"))),
                result("general", List.of(draft("api", "接口", "general")))),
                List.of("it", "general"));
        assertEquals(1, out.size(), "API 与 api 必须合并为一条");
        assertEquals("API", out.get(0).source(), "应保留信息量更大的原始形态");
        assertEquals(2, out.get(0).proposals());
        assertEquals(2, out.get(0).agreements());
    }

    // ─────────── 确定性裁决链 ───────────

    /** 有权威证据者优先，与输入顺序无关。 */
    @Test
    void authorityWinsRegardlessOfOrder() {
        var withAuth = result("it", List.of(draftAuth("API", "API", "it", "https://x.org/")));
        var without = result("general", List.of(draft("API", "应用程序接口", "general")));

        List<ScoredTerm> a = arbitrator.arbitrate(List.of(withAuth, without), List.of("it", "general"));
        List<ScoredTerm> b = arbitrator.arbitrate(List.of(without, withAuth), List.of("it", "general"));
        assertEquals(a.get(0).target(), b.get(0).target(), "结果不得依赖输入顺序");
        assertEquals("API", a.get(0).target(), "有权威证据的译法应胜出");
    }

    /** 同一输入多次运行结果必须完全一致。 */
    @Test
    void arbitrationIsDeterministic() {
        var rs = List.of(
                result("it", List.of(draft("Docker", "Docker", "it"))),
                result("general", List.of(draft("docker", "码头工人", "general"))),
                result("game", List.of(draft("DOCKER", "Docker", "game"))));
        String first = null;
        for (int i = 0; i < 5; i++) {
            List<ScoredTerm> out = arbitrator.arbitrate(rs, List.of("it", "general", "game"));
            String sig = out.get(0).source() + "→" + out.get(0).target();
            if (first == null) {
                first = sig;
            } else {
                assertEquals(first, sig, "第 " + i + " 次结果不一致");
            }
        }
    }

    /** 多数票胜出。 */
    @Test
    void majorityVoteWins() {
        List<ScoredTerm> out = arbitrator.arbitrate(List.of(
                result("it", List.of(draft("K8s", "Kubernetes", "it"))),
                result("general", List.of(draft("K8s", "Kubernetes", "general"))),
                result("game", List.of(draft("K8s", "K8s", "game")))),
                List.of("it", "general", "game"));
        assertEquals("Kubernetes", out.get(0).target());
        assertEquals(3, out.get(0).proposals());
        assertEquals(2, out.get(0).agreements());
    }

    /** 票数接近的不同译法记为冲突，交用户确认而非强行择一。 */
    @Test
    void closeVotesRecordConflict() {
        List<ScoredTerm> out = arbitrator.arbitrate(List.of(
                result("it", List.of(draft("Term", "译法甲", "it"))),
                result("general", List.of(draft("Term", "译法乙", "general")))),
                List.of("it", "general"));
        assertTrue(out.get(0).hasConflict(), "1:1 平票应记冲突");
        assertEquals(1, out.get(0).conflicts().size());
    }

    @Test
    void handlesEmptyInput() {
        assertTrue(arbitrator.arbitrate(List.of(), List.of()).isEmpty());
        assertTrue(arbitrator.arbitrate(null, List.of()).isEmpty());
        assertTrue(arbitrator.arbitrate(List.of(result("it", List.of())), List.of("it")).isEmpty());
    }

    /** 结果按置信度降序。 */
    @Test
    void sortsByConfidenceDesc() {
        List<ScoredTerm> out = arbitrator.arbitrate(List.of(
                result("it", List.of(
                        draftAuth("CRISPR", "CRISPR", "it", "https://nih.gov/"),
                        draft("thing", "东西", "it")))),
                List.of("it"));
        assertEquals(2, out.size());
        assertTrue(out.get(0).confidence() >= out.get(1).confidence());
    }

    // ─────────── ⑦ 分档 ───────────

    private static ScoredTerm scored(double conf, Strategy st) {
        return ScoredTerm.builder().source("X").sourceNorm("x").target("Y")
                .strategy(st).build().withConfidence(conf);
    }

    @Test
    void classifyThresholds() {
        assertEquals(TermState.VERIFIED, TermStateMachine.classify(scored(0.90, null)));
        assertEquals(TermState.VERIFIED, TermStateMachine.classify(scored(0.85, null)));
        assertEquals(TermState.PENDING, TermStateMachine.classify(scored(0.84, null)));
        assertEquals(TermState.PENDING, TermStateMachine.classify(scored(0.60, null)));
        assertEquals(TermState.EPHEMERAL, TermStateMachine.classify(scored(0.59, null)));
        assertEquals(TermState.EPHEMERAL, TermStateMachine.classify(scored(0.40, null)));
        assertEquals(TermState.DISCARD, TermStateMachine.classify(scored(0.39, null)));
        assertEquals(TermState.DISCARD, TermStateMachine.classify(scored(0.0, null)));
    }

    /**
     * <b>策略词优先规则</b>：分数落在「仅本次」档的策略词仍要入库锁一致性。
     * 这是对架构图自相矛盾之处的裁定。
     */
    @Test
    void coinedStrategyOverridesEphemeral() {
        ScoredTerm coined = scored(0.45, Strategy.TRANSLITERATE);
        assertEquals(TermState.UNVERIFIED, TermStateMachine.classify(coined),
                "音译词即使 0.45 分也要入库，锁定全篇一致性");
        assertTrue(TermStateMachine.classify(coined).persistent());

        // 而非策略词的同分数条目仍是 EPHEMERAL（不落库）
        assertEquals(TermState.EPHEMERAL, TermStateMachine.classify(scored(0.45, null)));
    }

    /** 权威译名不算「策略词」，走正常分档。 */
    @Test
    void authoritativeIsNotCoined() {
        assertEquals(TermState.EPHEMERAL,
                TermStateMachine.classify(scored(0.45, Strategy.AUTHORITATIVE)));
    }

    /** 低于丢弃线的策略词也要丢——一致性价值不能凌驾于「这词可能根本不该收录」之上。 */
    @Test
    void coinedBelowDiscardThresholdStillDiscarded() {
        assertEquals(TermState.DISCARD, TermStateMachine.classify(scored(0.30, Strategy.COINAGE)));
    }

    @Test
    void stateFlags() {
        assertTrue(TermState.VERIFIED.persistent());
        assertTrue(TermState.PENDING.persistent());
        assertTrue(TermState.UNVERIFIED.persistent());
        assertFalse(TermState.EPHEMERAL.persistent(), "EPHEMERAL 绝不落库");
        assertFalse(TermState.DISCARD.persistent());

        assertTrue(TermState.EPHEMERAL.usableNow(), "但本次翻译要用");
        assertFalse(TermState.DISCARD.usableNow());
    }
}
