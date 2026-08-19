package com.aifanyi.agent.node;

import com.aifanyi.agent.model.ScoredTerm;
import com.aifanyi.agent.model.Strategy;
import com.aifanyi.agent.model.TermState;

import java.util.List;
import java.util.regex.Pattern;

/**
 * ⑤ 术语分诊：决定一条候选术语「存不存库、启不启用」。<b>纯函数，无依赖，可穷尽单测。</b>
 *
 * <h2>为什么换掉了旧的置信度打分器</h2>
 * 旧版 ConfidenceScorer 给每个词算 0~1 的加权分，≥0.80 才入库，并按证据档位设「天花板」
 * （单方判断封顶 0.59）。两个机制方向相反：{@code score = min(加权分, 天花板)}，
 * 意味着加权分只能往下拉、天花板只能往下压，<b>没有任何特征能把分数推上去</b>。
 * 于是入库要同时通过 6 个独立的门（自报档 / 一致性 / 权威 / 词形 / 频次 / 未降级），
 * 每个门单看都合理，乘起来通过率趋近于零。
 *
 * <p>2026-08-15 生产日志（任务 226，TED 演讲）：场景只命中 general → 单专家，
 * 用户未配联网搜索 → 无权威特征，4 条候选（Sawubona / Zulu / apartheid / emotional agility）
 * 全部封顶 0.59，<b>落库 0 条</b>。不是模型不行，是分数上限够不着门槛。
 *
 * <h2>新机制：分两问，用规则不用打分</h2>
 * <pre>
 *   第一问 · 术语性：这词不记下来，下次会不会翻得不一样？（{@link #termhood}，四条规则）
 *   第二问 · 证据档：这个译法有几分佐证？（{@link #tier}，A/B/C 三档）
 * </pre>
 * 处置矩阵：
 * <pre>
 *              A 已核验权威   B 多方独立一致   C 单方判断
 *   术语性高      入库启用        入库启用       入库备选
 *   术语性低      入库启用        仅本次         仅本次
 * </pre>
 *
 * <p><b>旧机制唯一真正在防的东西完整保留</b>：单方判断（C 档）永远不能自动启用。
 * 区别只在于它的去向从「当场蒸发」变成「入库但不生效，等用户点头」——
 * 防模型自吹的初衷没变，但用户终于看得见 Agent 到底抽出了什么。
 *
 * <h2>不打分的三个理由</h2>
 * ① 打分要调参，而本项目没有任何标注数据可供校准，旧版那些 0.25/0.35/0.80 全是拍脑袋；
 * ② 规则错了能定位到具体哪一条，分数错了只能看到一个「0.73」；
 * ③ 规则能翻译成人话写进术语库的说明栏，用户看得懂「音译词，须锁定译法」，
 *    看不懂「置信度 0.73」。
 */
public final class TermTriage {

    /** 短语类术语的收录门槛：全文至少出现这么多次（出现一次的短语，本片内无一致性风险） */
    private static final int MIN_PHRASE_OCCURRENCES = 2;
    /** 中日文复合词的最短字数——2 字词多为普通名词，3 字往上才值得占术语库一行 */
    private static final int MIN_CJK_CHARS = 3;
    /** 超过这个长度基本是模型把整句话当术语抽出来了 */
    private static final int MAX_TERM_CHARS = 60;

    // ── 专名词形（沿用旧打分器的四个正则，它们本身是对的，错的是怎么用它们）──
    /** API、CRISPR、HTTP */
    private static final Pattern ALL_CAPS = Pattern.compile("^[A-Z][A-Z0-9]{1,9}$");
    /** JavaScript、PostgreSQL、GraphQL、OpenAI（允许尾部/中部连续大写段） */
    private static final Pattern CAMEL = Pattern.compile("^[A-Z][a-z]+(?:[A-Z]+[a-z]*)+$");
    /** sound-meaning、e-commerce */
    private static final Pattern HYPHEN = Pattern.compile("^[A-Za-z]+(?:-[A-Za-z0-9]+)+$");
    /** GPT-4、H2O、iPhone15 */
    private static final Pattern ALNUM_MIX = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z0-9.-]{2,20}$");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fa5\\u3040-\\u30ff\\uac00-\\ud7af]");

    private TermTriage() {
    }

    /** 证据档位。 */
    public enum Tier {
        /** 联网搜到权威来源，且该来源文本里确实出现了这个词（由 SearchConfig 代码核验，不采信模型自报） */
        A_AUTHORITY,
        /** 多方独立给出同一译法：同一次任务的多个专家，或此前另一个任务已独立得出同一译法 */
        B_CORROBORATED,
        /** 只有单方判断，无旁证 */
        C_SINGLE;

        public String zh() {
            return switch (this) {
                case A_AUTHORITY -> "权威佐证";
                case B_CORROBORATED -> "多方一致";
                case C_SINGLE -> "单方判断";
            };
        }
    }

    /**
     * 分诊结论。
     *
     * @param state  去向
     * @param tier   证据档
     * @param reason <b>给用户看的一句话</b>，会写进术语库的「说明」栏——
     *               这是不打分换来的直接好处：处置理由本身就是可读的
     */
    public record Verdict(TermState state, Tier tier, String reason) {
    }

    /**
     * 分诊。
     *
     * @param t            仲裁后的术语
     * @param historyAgrees 库里已有同一个词、同一个译法的<b>备选</b>条目
     *                      （= 此前另一个任务独立得出过同样结论，跨任务的一致性信号，
     *                      由 {@link TermStateMachine} 查库得出，本类保持纯函数）
     */
    public static Verdict triage(ScoredTerm t, boolean historyAgrees) {
        if (!valid(t)) {
            return new Verdict(TermState.DISCARD, Tier.C_SINGLE, "原文或译法无效");
        }
        Tier tier = tier(t, historyAgrees);
        String worth = termhood(t);

        // 术语性不足：不值得占用户术语库一行。但已经花钱抽出来了，注入本次翻译几乎零成本，
        // 至少保证本片内前后一致——所以是 EPHEMERAL 而不是 DISCARD。
        // 例外：有权威佐证说明这词确实有官方译名，那就是值得固化的，收下。
        if (worth == null) {
            return tier == Tier.A_AUTHORITY
                    ? new Verdict(TermState.ACTIVE, tier, "有权威来源佐证的既定译名")
                    : new Verdict(TermState.EPHEMERAL, tier, "未达收录标准，仅用于本次全篇统一");
        }

        // 存在竞争译法 → 无论证据多硬都不自动启用：各方看法不一时，
        // 「选错一个并永久锁死」比「让用户点一下」代价大得多。
        if (t.hasConflict()) {
            return new Verdict(TermState.CANDIDATE, tier,
                    worth + "；存在其他译法：" + String.join("、", nz(t.conflicts())) + "，待你裁决");
        }

        return switch (tier) {
            case A_AUTHORITY -> new Verdict(TermState.ACTIVE, tier, worth + "；有权威来源佐证");
            case B_CORROBORATED -> new Verdict(TermState.ACTIVE, tier, worth + "；"
                    + (historyAgrees ? "此前任务已独立得出同一译法" : "多位领域专家给出同一译法"));
            case C_SINGLE -> new Verdict(TermState.CANDIDATE, tier, worth + "；暂无旁证，待你确认");
        };
    }

    // ─────────────────────── 第一问 · 术语性 ───────────────────────

    /**
     * 这词值不值得记？换个问法：<b>不记下来，下次会不会翻得不一样？</b>
     *
     * <p>注意衡量的<b>不是</b>「这词高不高级」，而是「译法漂移的风险」。
     * 所以 apartheid → 种族隔离 这种有唯一标准译法的词反而不收——任何模型每次都翻对，
     * 记它只是让用户的术语库变长。而 Sawubona 这种祖鲁语问候，音译怎么写全凭当次发挥，
     * 不锁死下一集必然变样。
     *
     * @return 命中的理由（人话，写进术语库说明栏）；不值得记返回 null
     */
    static String termhood(ScoredTerm t) {
        // 规则 4 放在最前：库里已有近义条目时，一致性诉求压倒一切。
        // relatedIds 由 VectorIndexService.annotateRelated 算好（此前只用来提示，等于白算）。
        if (t.relatedIds() != null && !t.relatedIds().isEmpty()) {
            return "术语库中已有近义条目，需统一译法";
        }
        // 规则 1：译法是模型自拟的（音译/造词/音意结合/保留原文）→ 没有标准答案，必漂。
        // 这正是 Strategy.isCoined() 的 javadoc 当初写明、却从未接线的规则。
        // 注意不含 FREE（普通意译）：含义清楚的复合词各家译法本就趋同，不构成漂移风险。
        if (isSelfCoined(t.strategy())) {
            return "「" + t.strategy().zh() + "」类译法，无标准答案，须锁定全篇一致";
        }
        // 规则 2：词形本身就是专名信号
        if (isProperNounShape(t.source())) {
            return "缩写/专名词形，通用模型易译错或译不统一";
        }
        // 规则 3：多词短语且反复出现 —— 短语的译法组合空间大，最容易前后不一
        if (isPhrase(t.source()) && t.occurrences() >= MIN_PHRASE_OCCURRENCES) {
            return "复合术语，全文出现 " + t.occurrences() + " 次，须前后一致";
        }
        return null;
    }

    /** 无标准答案、纯属当次自拟的策略。FREE（意译）与 AUTHORITATIVE（有权威译名）不算。 */
    static boolean isSelfCoined(Strategy s) {
        return s == Strategy.TRANSLITERATE || s == Strategy.SOUND_MEANING
                || s == Strategy.COINAGE || s == Strategy.KEEP_ORIGINAL;
    }

    /** 词形是否天然是专名：缩写 / 驼峰 / 连字符复合 / 字母数字混合。 */
    static boolean isProperNounShape(String source) {
        if (source == null) {
            return false;
        }
        String s = source.trim();
        return ALL_CAPS.matcher(s).matches() || CAMEL.matcher(s).matches()
                || HYPHEN.matcher(s).matches() || ALNUM_MIX.matcher(s).matches();
    }

    /**
     * 是否为「复合/多词」形态。
     * <p>中日韩没有词间空格，按字数判断；拉丁语系按空格切词数判断。
     */
    static boolean isPhrase(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        String s = source.trim();
        if (CJK.matcher(s).find()) {
            return s.replaceAll("\\s+", "").length() >= MIN_CJK_CHARS;
        }
        return s.split("\\s+").length >= 2;
    }

    // ─────────────────────── 第二问 · 证据档 ───────────────────────

    /**
     * 这个译法有几分佐证？
     *
     * <p><b>降级（超时/部分结果）不再额外惩罚</b>：旧版对降级来源统一打 0.85 折。
     * 但降级只会让证据<b>变少</b>（专家没跑完 → agreements 偏低），证据少自然落到低档，
     * 再罚一次是双重惩罚——这正是旧版「两专家一致但有降级 = 0.765 &lt; 0.80」的来源。
     */
    static Tier tier(ScoredTerm t, boolean historyAgrees) {
        if (t.hasAuthority()) {
            return Tier.A_AUTHORITY;
        }
        if (t.agreements() >= 2 || historyAgrees) {
            return Tier.B_CORROBORATED;
        }
        return Tier.C_SINGLE;
    }

    private static boolean valid(ScoredTerm t) {
        if (t.source() == null || t.source().isBlank()
                || t.target() == null || t.target().isBlank()) {
            return false;
        }
        // 抽成整句话了——这类条目注入 prompt 只会干扰翻译
        return t.source().trim().length() <= MAX_TERM_CHARS;
    }

    private static List<String> nz(List<String> l) {
        return l == null ? List.of() : l;
    }
}
