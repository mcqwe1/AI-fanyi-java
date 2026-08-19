package com.aifanyi.agent.llm;

import com.aifanyi.agent.model.TermDraft;
import com.aifanyi.agent.profile.AgentProfile;
import com.aifanyi.agent.search.SearchHit;

import java.util.List;
import java.util.Map;

/**
 * Agent 各节点的提示词（harness 上下文管理层）。
 * <p>协议约定：模型只返回 JSON 对象，不带 markdown 围栏、不带解释。
 * 解析侧 {@link JsonEnvelope} 已做三级兜底，但提示词仍要把要求写死——
 * 从源头减少畸形返回比事后补救便宜。
 */
public final class Prompts {

    private Prompts() {
    }

    // ─────────────────────────── ③ 场景推测 ───────────────────────────

    /**
     * 主 Agent 在档案池中做匹配（不是选写死的 Agent）。
     * 只跑一次、只看首段摘要，结果缓存到任务上，重试不重跑。
     */
    public static AgentJsonClient.Prompt sceneInfer(String digest, List<AgentProfile> pool,
                                                    String sourceLang, String targetLang) {
        StringBuilder sys = new StringBuilder();
        sys.append("你是翻译任务的领域分析师。任务：判断给定文本属于哪些专业领域，以便激活对应的术语专家。\n\n");
        sys.append("【可选领域档案】\n");
        for (AgentProfile p : pool) {
            sys.append("- ").append(p.getDomainCode()).append("：").append(p.getName());
            if (p.getJudgeCriteria() != null && !p.getJudgeCriteria().isBlank()) {
                sys.append(" —— ").append(p.getJudgeCriteria());
            }
            sys.append('\n');
        }
        sys.append("\n【判定要求】\n")
                .append("1. 每个档案的「——」后面是它的判定标准，这是唯一判定依据：")
                .append("文本的主要内容明确落在该标准描述的范围内才算命中；")
                .append("只是偶尔提到一两个相关词、或仅凭主题联想沾边的，一律不算命中。\n")
                .append("2. 可以命中多个领域，但每个命中都必须能引用文本里的具体内容作为依据（写进 why）；\n")
                .append("3. 每个命中给 0~1 的匹配分，只返回分数 ≥0.35 的；宁可少激活也不要错激活——")
                .append("激活一个不相关的专家会污染术语表；\n")
                .append("4. 如果都不匹配，返回 general 兜底，并在 draft 字段给出这个新领域的档案草稿；\n")
                .append("5. 不要臆测：只依据文本里真实出现的内容判断。\n\n");
        sys.append("【输出格式】只返回 JSON，不要任何解释或代码块：\n")
                .append("{\"matches\":[{\"domain\":\"领域code\",\"score\":0.9,\"why\":\"一句话依据\"}],")
                .append("\"draft\":{\"domainCode\":\"新领域code\",\"name\":\"领域名\",")
                .append("\"judgeCriteria\":\"判定标准\",\"conventions\":\"该领域翻译惯例\",")
                .append("\"searchHints\":\"搜索提示词;分号分隔\"}}\n")
                .append("（draft 仅在所有内置领域都不合适时给出，否则省略该字段）");

        String user = "源语言：" + nz(sourceLang) + "，目标语言：" + nz(targetLang) + "\n\n文本摘要：\n" + digest;
        return new AgentJsonClient.Prompt(sys.toString(), user, 0.1);
    }

    // ─────────────────────── ④ 步骤 A：提取候选 ───────────────────────

    /**
     * 提取候选术语 + 判定是否需要联网核实（合并成一次调用）。
     * <p>架构图把「提取候选」和「需联网?」画成两个 [1次]，但它们是对同一批数据的两个判断，
     * 拆成两次调用只增加一次往返延迟，不增加任何信息——30 秒预算下这次往返很宝贵。
     *
     * <p><b>2026-08 增补 strategy 字段</b>：它原先只在步骤 C 产出，而步骤 C 只处理
     * needSearch=true 的词——于是不需要联网的词永远没有 strategy，
     * {@link com.aifanyi.agent.node.TermTriage} 的规则 1（自拟译法必须锁定）对它们失效。
     * 让抽词阶段一并吐出来，<b>不增加任何调用次数</b>，只是多返回一个字段。
     */
    public static AgentJsonClient.Prompt extract(AgentProfile profile, String digest,
                                                  String sourceLang, String targetLang) {
        StringBuilder sys = new StringBuilder();
        sys.append("你是「").append(profile.getName()).append("」领域的翻译术语专家。\n");
        sys.append("任务：从文本中找出翻译成").append(nz(targetLang))
                .append("时必须【全篇统一、译法正确】的专业术语与专有名词。\n\n");

        // 领域边界：用户在档案里写的「什么内容归我」是硬约束。没有这段时，
        // 每个专家都会对全文抽词，把别的领域的内容也拿去处理（用户实际反馈的问题）。
        if (notBlank(profile.getJudgeCriteria())) {
            sys.append("【你的领域边界，必须严格遵守】\n")
                    .append("你只负责满足以下判定标准的内容：").append(profile.getJudgeCriteria()).append('\n')
                    .append("文本中不属于该范围的词——哪怕它看起来是术语——也不要提取，那是其他专家的职责；")
                    .append("越界提取会污染术语表。拿不准是否属于你的范围时，不提。\n\n");
        }

        if (notBlank(profile.getConventions())) {
            sys.append("【本领域翻译惯例，必须遵循】\n").append(profile.getConventions()).append("\n\n");
        }
        if (notBlank(profile.getConflictPriority())) {
            sys.append("【译名优先级】").append(profile.getConflictPriority()).append("\n\n");
        }

        sys.append("【排除法——这是关键】\n")
                .append("只提「通用翻译模型很可能译错或译不统一」的词。")
                .append("普通名词、动词、形容词、日常短语一律不提——")
                .append("任何模型都不会把 hospital 译错，提它只是浪费。")
                .append("宁缺毋滥：一段几千字的文本，通常只有 3~15 个词值得进表。\n\n");

        sys.append("【每个候选给出】\n")
                .append("- source：原文形态（保持文本中的原样，不要改大小写）\n")
                .append("- target：你的初步译法\n")
                .append("- strategy：这个译法是怎么来的，五选一——\n")
                .append("   AUTHORITATIVE 该词有公认/官方译名，你直接采用了它\n")
                .append("   KEEP_ORIGINAL 保留原文不译（业界通用缩写、品牌官方写法）\n")
                .append("   TRANSLITERATE 音译（人名、地名、无实义生造词）\n")
                .append("   FREE 意译（含义清楚的复合词，按意思译）\n")
                .append("   SOUND_MEANING 音意结合 / COINAGE 造词（前几种都不合适时）\n")
                .append("  这个字段决定该词要不要被长期收录：没有公认答案、纯属你当次自拟的译法")
                .append("（音译/造词/保留原文）必须锁定下来，否则下次会译成别的样子。请如实填。\n")
                .append("- needSearch：是否需要联网核实。")
                .append("你确信有公认译名且自己知道 → false；")
                .append("生僻专名、可能有官方译名但你不确定、或存在多种流行译法 → true\n")
                .append("- confidence：high/medium/low，你对这个译法的把握\n")
                .append("- queries：needSearch=true 时给 1~2 个搜索词（用于查证官方译名）\n\n");

        if (notBlank(profile.getSearchHints())) {
            sys.append("【搜索词可参考这些角度】").append(profile.getSearchHints()).append("\n\n");
        }

        sys.append("【输出格式】只返回 JSON，不要解释、不要代码块：\n")
                .append("{\"status\":\"DONE\",\"terms\":[{\"source\":\"...\",\"target\":\"...\",")
                .append("\"strategy\":\"TRANSLITERATE\",")
                .append("\"needSearch\":true,\"confidence\":\"high\",\"queries\":[\"...\"]}]}\n")
                .append("没有值得收录的术语就返回 {\"status\":\"DONE\",\"terms\":[]}");

        String user = "源语言：" + nz(sourceLang) + "，目标语言：" + nz(targetLang) + "\n\n文本：\n" + digest;
        return new AgentJsonClient.Prompt(sys.toString(), user, 0.2);
    }

    // ─────────────── ④ 步骤 C：权威判定 + 证据 + 策略 ───────────────

    /**
     * 依据搜索结果与上下文证据定终稿。
     * 合并了架构图的「权威命中?」「证据挖掘」「策略五选一」三个判断——同一批数据，一次说清。
     */
    public static AgentJsonClient.Prompt resolve(AgentProfile profile, List<TermDraft> terms,
                                                  Map<String, List<SearchHit>> hits,
                                                  Map<String, List<String>> evidence,
                                                  String sourceLang, String targetLang) {
        StringBuilder sys = new StringBuilder();
        sys.append("你是「").append(profile.getName()).append("」领域的翻译术语专家。\n");
        sys.append("任务：根据提供的搜索结果与上下文证据，为每个术语定出最终译法。\n\n");

        if (notBlank(profile.getConventions())) {
            sys.append("【本领域翻译惯例，必须遵循】\n").append(profile.getConventions()).append("\n\n");
        }

        sys.append("【定译规则——按顺序执行】\n")
                .append("① 搜索结果里有权威/官方译名 → 直接采用，strategy=AUTHORITATIVE，")
                .append("evidence 填来源片段，authorityUrl 填链接。切勿对已有通用译名的专名自创新译。\n")
                .append("② 没有现成译名 → 从下列策略中选一个，并说明理由：\n")
                .append("   KEEP_ORIGINAL 保留原文（业界通用缩写、品牌官方写法）\n")
                .append("   TRANSLITERATE 音译（人名、无实义生造词）\n")
                .append("   FREE 意译（含义清楚的复合词）\n")
                .append("   SOUND_MEANING 音意结合\n")
                .append("   COINAGE 造词（前几种都不合适时）\n")
                .append("③ 铁律：target 必须是").append(nz(targetLang))
                .append("，除非该专名在").append(nz(targetLang)).append("受众中本就通用原文写法。\n")
                .append("④ 搜索结果与你的先验冲突时，以搜索到的权威来源为准。\n")
                .append("⑤ 搜索结果明显不相关（搜到了同名的其他事物）→ 忽略它，按②处理。\n\n");

        sys.append("【顺带反馈】如果你在核实过程中发现本领域档案的惯例有误、")
                .append("或某些搜索角度特别有效/无效，在 profileProposal 里提出建议（可选）。\n\n");

        sys.append("【输出格式】只返回 JSON，不要解释、不要代码块：\n")
                .append("{\"terms\":[{\"source\":\"原文\",\"target\":\"终译\",")
                .append("\"strategy\":\"AUTHORITATIVE|KEEP_ORIGINAL|TRANSLITERATE|FREE|SOUND_MEANING|COINAGE\",")
                .append("\"evidence\":\"依据片段\",\"authorityUrl\":\"链接或空\",")
                .append("\"confidence\":\"high|medium|low\",")
                .append("\"reason\":\"一句话向用户说明：这个词指什么、为何采用该译法（这句话会作为术语库里的说明展示）\"}],")
                .append("\"profileProposal\":{\"conventionFixes\":[\"...\"],")
                .append("\"addSearchHints\":[\"...\"],\"dropSearchHints\":[\"...\"]}}\n")
                .append("source 必须与输入完全一致，每个输入术语返回恰好一条。");

        StringBuilder user = new StringBuilder();
        user.append("源语言：").append(nz(sourceLang)).append("，目标语言：").append(nz(targetLang)).append("\n\n");
        for (TermDraft t : terms) {
            user.append("──────────\n");
            user.append("术语：").append(t.source()).append("\n");
            user.append("初步译法：").append(t.target() == null ? "（无）" : t.target()).append("\n");
            user.append("全文出现次数：").append(t.occurrences()).append("\n");

            List<String> ev = evidence == null ? null : evidence.get(t.source());
            if (ev != null && !ev.isEmpty()) {
                user.append("上下文定义句：\n");
                for (String s : ev) {
                    user.append("  · ").append(s).append('\n');
                }
            }
            List<SearchHit> hs = hits == null ? null : hits.get(t.source());
            if (hs != null && !hs.isEmpty()) {
                user.append("搜索结果：\n");
                for (SearchHit h : hs) {
                    user.append("  · [").append(h.domain()).append("] ").append(h.title()).append('\n');
                    if (notBlank(h.snippet())) {
                        user.append("    ").append(trim(h.snippet(), 300)).append('\n');
                    }
                    if (notBlank(h.url())) {
                        user.append("    ").append(h.url()).append('\n');
                    }
                }
            } else {
                user.append("搜索结果：（无——请依据上下文证据与你的知识按策略定译）\n");
            }
        }
        return new AgentJsonClient.Prompt(sys.toString(), user.toString(), 0.2);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "自动" : s;
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String f = s.replaceAll("\\s+", " ").trim();
        return f.length() <= max ? f : f.substring(0, max) + "…";
    }
}
