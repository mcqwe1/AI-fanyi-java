package com.aifanyi.agent.node;

import com.aifanyi.agent.model.ScoredTerm;
import com.aifanyi.agent.model.Strategy;
import com.aifanyi.agent.model.TermState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分诊规则的穷尽单测。
 *
 * <p>这是「用规则不用打分」换来的直接好处：每条规则都能单独钉死，
 * 出问题能定位到具体哪一条。旧版加权打分器只能测出「0.73」，
 * 没人说得清那 0.73 是怎么来的、改哪个权重能修好。
 *
 * <p>靶子取自 2026-08-15 生产日志（任务 226，TED 演讲《情绪敏捷力》），
 * 那次抽出 4 个词、落库 0 条——现在它们必须各归其位。
 */
class TermTriageTest {

    private static ScoredTerm.Builder base(String source, String target) {
        return ScoredTerm.builder().source(source).sourceNorm(Arbitrator.norm(source)).target(target);
    }

    private static ScoredTerm term(String source, String target, Strategy st, int occ) {
        return base(source, target).strategy(st).occurrences(occ).build();
    }

    // ─────────────── 回归：那 4 个被白扔的词 ───────────────

    /**
     * 任务 226 的四个词各自的去向。
     * 旧版：单专家 + 未配搜索 → 天花板 0.59 &lt; 入库线 0.80 → 四个全扔。
     */
    @Test
    void ted226TermsGoWhereTheyShould() {
        // 祖鲁语问候，音译，没有标准答案 —— 最该锁死的词
        assertEquals(TermState.CANDIDATE,
                TermTriage.triage(term("Sawubona", "萨乌博纳", Strategy.TRANSLITERATE, 3), false).state());
        // 民族/语言名，音译
        assertEquals(TermState.CANDIDATE,
                TermTriage.triage(term("Zulu", "祖鲁", Strategy.TRANSLITERATE, 2), false).state());
        // 全片核心概念，多词短语，反复出现
        assertEquals(TermState.CANDIDATE,
                TermTriage.triage(term("emotional agility", "情绪敏捷力", Strategy.FREE, 12), false).state());
        // 有唯一标准译法的意译单词 —— 不记它是对的，任何模型每次都翻对
        assertEquals(TermState.EPHEMERAL,
                TermTriage.triage(term("apartheid", "种族隔离", Strategy.FREE, 2), false).state());
    }

    // ─────────────── 第一问 · 术语性四条规则 ───────────────

    /** 规则 1：音译/造词/音意结合/保留原文 —— 无标准答案，必须锁定。 */
    @Test
    void rule1SelfCoinedStrategies() {
        assertNotNull(TermTriage.termhood(term("Sawubona", "萨乌博纳", Strategy.TRANSLITERATE, 1)));
        assertNotNull(TermTriage.termhood(term("blurb", "书腰语", Strategy.COINAGE, 1)));
        assertNotNull(TermTriage.termhood(term("hacker", "黑客", Strategy.SOUND_MEANING, 1)));
        assertNotNull(TermTriage.termhood(term("token", "token", Strategy.KEEP_ORIGINAL, 1)));
        // 普通意译不算：含义清楚的复合词各家译法本就趋同
        assertNull(TermTriage.termhood(term("goodwill", "善意", Strategy.FREE, 1)));
        // 有公认译名的也不算：不记也不会漂
        assertNull(TermTriage.termhood(term("physics", "物理学", Strategy.AUTHORITATIVE, 1)));
    }

    /** 规则 2：词形本身就是专名，出现一次也收（下一集还会出现）。 */
    @Test
    void rule2ProperNounShapes() {
        assertTrue(TermTriage.isProperNounShape("API"));
        assertTrue(TermTriage.isProperNounShape("CRISPR"));
        assertTrue(TermTriage.isProperNounShape("PostgreSQL"));
        assertTrue(TermTriage.isProperNounShape("JavaScript"));
        assertTrue(TermTriage.isProperNounShape("e-commerce"));
        assertTrue(TermTriage.isProperNounShape("GPT-4"));
        assertTrue(TermTriage.isProperNounShape("H2O"));

        assertFalse(TermTriage.isProperNounShape("hospital"));
        assertFalse(TermTriage.isProperNounShape("Zulu"), "仅首字母大写不构成专名词形，靠规则 1 兜");
        assertFalse(TermTriage.isProperNounShape(null));
        assertFalse(TermTriage.isProperNounShape(""));

        // 出现 1 次也收
        assertNotNull(TermTriage.termhood(term("API", "接口", Strategy.FREE, 1)));
    }

    /** 规则 3：多词短语 + 反复出现。单次出现的短语本片内无一致性风险。 */
    @Test
    void rule3RecurringPhrases() {
        assertNotNull(TermTriage.termhood(term("emotional agility", "情绪敏捷力", Strategy.FREE, 2)));
        assertNull(TermTriage.termhood(term("emotional agility", "情绪敏捷力", Strategy.FREE, 1)),
                "只出现一次的短语不占术语库");
        assertNull(TermTriage.termhood(term("hospital", "医院", Strategy.FREE, 99)),
                "普通单词再高频也不收——任何模型都不会译错");
    }

    /** 规则 3 的中日韩分支：没有词间空格，按字数判断。 */
    @Test
    void rule3CjkUsesCharCount() {
        assertTrue(TermTriage.isPhrase("守破离"));
        assertTrue(TermTriage.isPhrase("物の哀れ"));
        assertFalse(TermTriage.isPhrase("茶道"), "2 字词多为普通名词");
        assertNotNull(TermTriage.termhood(term("守破离", "守破离", Strategy.FREE, 2)));
        assertNull(TermTriage.termhood(term("茶道", "茶道", Strategy.FREE, 5)));
    }

    /** 规则 4：库里已有近义条目 → 一致性诉求压倒一切，优先于其余规则。 */
    @Test
    void rule4RelatedEntriesForceKeeping() {
        ScoredTerm plain = term("hospital", "医院", Strategy.FREE, 1);
        assertNull(TermTriage.termhood(plain), "前提：这词本来不该收");
        ScoredTerm related = plain.withRelated(List.of(42L));
        assertNotNull(TermTriage.termhood(related), "库里有近义条目就必须收，否则新旧译法会打架");
    }

    // ─────────────── 第二问 · 证据三档 ───────────────

    @Test
    void tierRanking() {
        ScoredTerm authority = base("API", "接口").authorityUrl("https://w3.org/x").build();
        assertEquals(TermTriage.Tier.A_AUTHORITY, TermTriage.tier(authority, false));

        ScoredTerm agreed = base("API", "接口").agreements(2).proposals(2).build();
        assertEquals(TermTriage.Tier.B_CORROBORATED, TermTriage.tier(agreed, false));

        ScoredTerm single = base("API", "接口").agreements(1).proposals(1).build();
        assertEquals(TermTriage.Tier.C_SINGLE, TermTriage.tier(single, false));

        // 跨任务：此前另一个任务独立得出同一译法，等同于多方一致
        assertEquals(TermTriage.Tier.B_CORROBORATED, TermTriage.tier(single, true));
    }

    /** 降级不再额外惩罚：证据少自然落低档，旧版再打 0.85 折是双重惩罚。 */
    @Test
    void degradedIsNotPunishedTwice() {
        ScoredTerm degraded = base("API", "接口").agreements(2).proposals(2).degraded(true).build();
        assertEquals(TermTriage.Tier.B_CORROBORATED, TermTriage.tier(degraded, false));
        assertEquals(TermState.ACTIVE, TermTriage.triage(degraded, false).state(),
                "旧版此处为 0.90×0.85=0.765 < 0.80，硬生生卡掉");
    }

    // ─────────────── 处置矩阵 ───────────────

    @Test
    void matrixHighTermhood() {
        ScoredTerm auth = base("CRISPR", "基因编辑技术").strategy(Strategy.TRANSLITERATE)
                .authorityUrl("https://nih.gov/x").build();
        assertEquals(TermState.ACTIVE, TermTriage.triage(auth, false).state());

        ScoredTerm agreed = base("CRISPR", "CRISPR").strategy(Strategy.KEEP_ORIGINAL)
                .agreements(2).proposals(2).build();
        assertEquals(TermState.ACTIVE, TermTriage.triage(agreed, false).state());

        ScoredTerm single = base("CRISPR", "CRISPR").strategy(Strategy.KEEP_ORIGINAL).build();
        assertEquals(TermState.CANDIDATE, TermTriage.triage(single, false).state(),
                "单方判断永远不自动启用——这是旧机制唯一真正在防的东西，必须保留");
    }

    @Test
    void matrixLowTermhood() {
        ScoredTerm auth = base("physics", "物理学").strategy(Strategy.FREE)
                .authorityUrl("https://britannica.com/x").build();
        assertEquals(TermState.ACTIVE, TermTriage.triage(auth, false).state(),
                "有权威佐证的既定译名值得固化");

        ScoredTerm agreed = base("physics", "物理学").strategy(Strategy.FREE)
                .agreements(2).proposals(2).build();
        assertEquals(TermState.EPHEMERAL, TermTriage.triage(agreed, false).state());

        ScoredTerm single = base("physics", "物理学").strategy(Strategy.FREE).build();
        assertEquals(TermState.EPHEMERAL, TermTriage.triage(single, false).state(),
                "术语性不足的也不丢弃——已经花钱抽出来了，注入本次几乎零成本");
    }

    /** 有竞争译法时无论证据多硬都不自动启用：选错并永久锁死的代价更大。 */
    @Test
    void conflictNeverAutoActivates() {
        ScoredTerm conflicted = base("token", "词元").strategy(Strategy.KEEP_ORIGINAL)
                .authorityUrl("https://w3.org/x")
                .agreements(2).proposals(3)
                .conflicts(List.of("令牌")).build();
        TermTriage.Verdict v = TermTriage.triage(conflicted, false);
        assertEquals(TermState.CANDIDATE, v.state());
        assertTrue(v.reason().contains("令牌"), "理由里要写出竞争译法，用户才知道在裁决什么");
    }

    // ─────────────── 边界 ───────────────

    @Test
    void discardsInvalidTerms() {
        assertEquals(TermState.DISCARD,
                TermTriage.triage(base("", "译法").build(), false).state());
        assertEquals(TermState.DISCARD,
                TermTriage.triage(base("词", "  ").build(), false).state());
        assertEquals(TermState.DISCARD,
                TermTriage.triage(base("这是一整句被模型误当成术语抽出来的话".repeat(4), "x").build(), false).state(),
                "抽成整句的条目注入 prompt 只会干扰翻译");
    }

    /** 理由必须是人话——它会原样显示在术语库的「说明」栏。 */
    @Test
    void reasonIsHumanReadable() {
        TermTriage.Verdict v = TermTriage.triage(
                term("Sawubona", "萨乌博纳", Strategy.TRANSLITERATE, 3), false);
        assertTrue(v.reason().contains("音译"), "实际为：" + v.reason());
        assertTrue(v.reason().contains("待你确认"), "实际为：" + v.reason());
        assertFalse(v.reason().matches(".*0\\.\\d+.*"), "不该再出现任何裸分数");
    }
}
