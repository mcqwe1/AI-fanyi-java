package com.aifanyi.agent.node;

import com.aifanyi.agent.AgentBudget;
import com.aifanyi.agent.llm.AgentJsonClient;
import com.aifanyi.agent.llm.Parsers;
import com.aifanyi.agent.llm.Prompts;
import com.aifanyi.agent.model.*;
import com.aifanyi.agent.profile.AgentProfile;
import com.aifanyi.agent.search.SearchConfig;
import com.aifanyi.agent.search.SearchHit;
import com.aifanyi.agent.search.SearchProvider;
import com.aifanyi.agent.trace.TraceRecorder;
import com.aifanyi.llm.LlmConfig;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * ④ 一个子 Agent 实例 = 统一执行器 × 注入一份领域档案。
 *
 * <p><b>固定 DAG，无自由循环：</b>
 * <pre>
 *   A 提取候选 + 判定需联网  [1 次 LLM]
 *     ├ 无候选 / 不需联网 → 终稿
 *     └ 需联网 → 预算够跑完 B+C 吗？不够就地收尾
 *   B 搜索 ≤2 次（纯代码执行）→ 封口，此后禁止再搜
 *   C 权威判定 + 证据 + 策略五选一  [1 次 LLM]
 * </pre>
 *
 * <p><b>三条硬约束（全部代码兜底，不靠模型自觉）：</b>
 * 工具 ≤3 次、搜索 ≤2 次、墙钟 30 秒；超限一律「已有结果收尾」而非丢弃。
 *
 * <p><b>本类的 call() 永不抛异常</b>——于是 Future.get() 永不抛 ExecutionException，
 * orchestrator 只需处理 TimeoutException，消除了一整类「异常吃掉部分结果」的 bug。
 */
@Slf4j
public final class SubAgentTask implements Callable<SubAgentResult> {

    /** 步骤 C 至少需要这么多毫秒才值得发起 */
    private static final long MIN_LLM_MS = 6_000;
    /** 进入搜索前必须为步骤 C 预留的余量——否则搜完就没时间定译，白搜 */
    private static final long STEP_C_RESERVE_MS = 10_000;
    /** 单次搜索的超时上限 */
    private static final long SEARCH_TIMEOUT_MS = 8_000;

    private final SubAgentContext ctx;
    private final AgentBudget budget;
    private final AgentJsonClient llm;
    private final SearchProvider search;
    private final TraceRecorder trace;
    private final TraceRecorder.Buffer traceBuf;

    // ── 部分结果累加器：orchestrator 在 submit 之前就持有本对象引用，
    //    因此即便 Future 被 cancel，也能通过 snapshot() 取回已提交的成果
    private final List<TermDraft> committed = new ArrayList<>();
    private volatile Step step = Step.INIT;
    private volatile DegradeReason degrade;
    private volatile String proposalJson;
    private volatile long tokens;
    private final long startedAt = System.currentTimeMillis();

    public SubAgentTask(SubAgentContext ctx, AgentBudget budget, AgentJsonClient llm,
                        SearchProvider search, TraceRecorder trace, TraceRecorder.Buffer traceBuf) {
        this.ctx = ctx;
        this.budget = budget;
        this.llm = llm;
        this.search = search;
        this.trace = trace;
        this.traceBuf = traceBuf;
    }

    @Override
    public SubAgentResult call() {
        String code = ctx.profile().getDomainCode();
        try {
            // ── 步骤 A：提取候选 + 判定需联网 ──
            advance(Step.EXTRACTING);
            AgentJsonClient.Prompt pa = Prompts.extract(
                    ctx.profile(), ctx.digest(), ctx.sourceLang(), ctx.targetLang());
            AgentJsonClient.Call a = llm.callJson(pa, ctx.subLlm(), budget, "A/" + code);
            tokens += a.totalTokens();
            trace.llmFull(traceBuf, "专家抽词 A/" + code, ctx.subLlm().model(),
                    pa.system(), pa.user(), a.ok() ? a.json().toString() : a.stopReason(),
                    a.stopReason(), a.elapsedMs(), a.promptTokens(), a.completionTokens());
            trace.record(traceBuf, "SUBAGENT", code, "extract:" + ctx.digestChars() + "字",
                    a.ok() ? summarize(a.json()) : "解析失败", a.elapsedMs(),
                    a.promptTokens(), a.completionTokens(), a.totalTokens(),
                    a.stopReason(), !a.ok());
            trace.flush(traceBuf);     // 运行现场实时性：抽词一完成，前端下个轮询就能看到

            if (!a.ok()) {
                // 步骤 A 就失败 = 什么都没有，这是真正的空结果
                return finish(Step.WRAPPED_UP, mapReason(a.stopReason()));
            }
            List<TermDraft> drafts = Parsers.parseExtract(a.json(), code);
            // 出现次数由代码在全文精确统计（免费信号，不受摘要抽样影响）
            drafts = drafts.stream()
                    .map(d -> d.withOccurrences(EvidenceMiner.countOccurrences(ctx.fullText(), d.source())))
                    .toList();
            commit(drafts);
            advance(Step.EXTRACTED);
            if (drafts.isEmpty()) {
                return finish(Step.DONE, null);
            }

            // 挑出需要联网核实的：按出现次数降序，代码截断到预算允许的条数
            List<TermDraft> need = drafts.stream()
                    .filter(TermDraft::needSearch)
                    .sorted(Comparator.comparingInt(TermDraft::occurrences).reversed())
                    .limit(ctx.maxSearchTerms())
                    .toList();

            // 不需要联网 → 直接终稿（省掉步骤 C 的一次往返）
            if (need.isEmpty()) {
                return finish(Step.DONE, null);
            }
            // 时间不够跑完 B+C：就地收尾，保住步骤 A 的成果
            if (!budget.deadline().hasRoomFor(STEP_C_RESERVE_MS)) {
                log.debug("[{}] 预算不足以跑完搜索+定译，用已有 {} 条收尾", code, committed.size());
                return finish(Step.WRAPPED_UP, DegradeReason.BUDGET);
            }

            // ── 步骤 B：搜索（≤2 次；provider 不可用 = 0 命中，不是错误）──
            advance(Step.SEARCHING);
            Map<String, List<SearchHit>> hits = runSearches(need, code);
            advance(Step.SEARCHED);
            budget.sealSearch();       // 「查不到=换策略，而非继续搜」变成物理事实

            // ── 步骤 C：权威判定 + 证据挖掘 + 策略五选一 ──
            if (!budget.deadline().hasRoomFor(MIN_LLM_MS)) {
                return finish(Step.WRAPPED_UP, DegradeReason.BUDGET);
            }
            advance(Step.RESOLVING);
            // 证据挖掘：纯正则、零 token，LLM 与搜索都不可用时它仍能提供真实依据
            Map<String, List<String>> evidence = EvidenceMiner.mine(ctx.fullText(), need);
            AgentJsonClient.Prompt pc = Prompts.resolve(
                    ctx.profile(), need, hits, evidence, ctx.sourceLang(), ctx.targetLang());
            AgentJsonClient.Call c = llm.callJson(pc, ctx.subLlm(), budget, "C/" + code);
            tokens += c.totalTokens();
            trace.llmFull(traceBuf, "专家定译 C/" + code, ctx.subLlm().model(),
                    pc.system(), pc.user(), c.ok() ? c.json().toString() : c.stopReason(),
                    c.stopReason(), c.elapsedMs(), c.promptTokens(), c.completionTokens());
            trace.record(traceBuf, "SUBAGENT", code,
                    "resolve:" + need.size() + "词/" + countHits(hits) + "命中",
                    c.ok() ? summarize(c.json()) : "解析失败", c.elapsedMs(),
                    c.promptTokens(), c.completionTokens(), c.totalTokens(),
                    c.stopReason(), !c.ok());
            trace.flush(traceBuf);

            if (c.ok()) {
                // 合并回全量列表：模型漏返回的条目保留步骤 A 结果，不丢词
                // 再用「实际检索结果」核验权威声明——模型自报的 AUTHORITATIVE / authorityUrl
                // 不能直接兑换 0.95 天花板，否则等于把自报当证据（见 SearchConfig.verifyAuthorityUrl）
                commit(verifyAuthority(Parsers.mergeResolve(committed(), c.json()), hits));
                proposalJson = Parsers.extractProposal(c.json());
                return finish(Step.DONE, null);
            }
            // 定译失败但候选还在 → 部分结果收尾
            return finish(Step.WRAPPED_UP, mapReason(c.stopReason()));

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return finish(Step.WRAPPED_UP, DegradeReason.CANCELLED);
        } catch (Throwable t) {
            // 降级不抛错（照 OpenAiTranslator 的哲学）：一个子 Agent 炸了不该影响其余
            log.warn("子Agent[{}] 异常，用已有 {} 条结果收尾: {}", code, committed.size(), t.toString());
            return finish(Step.WRAPPED_UP, DegradeReason.ERROR);
        }
    }

    /** 执行搜索：每个术语取其首个 query，全局受 budget 限制。 */
    private Map<String, List<SearchHit>> runSearches(List<TermDraft> need, String code)
            throws InterruptedException {
        Map<String, List<SearchHit>> out = new LinkedHashMap<>();
        SearchConfig cfg = ctx.searchCfg();
        if (!cfg.enabled()) {
            return out;                          // 未配搜索 = 正常降级路径，不记降级
        }
        for (TermDraft t : need) {
            if (!budget.trySearch()) {
                break;                           // 额度用尽/封口/到点 → 停，已搜到的照用
            }
            String q = buildQuery(t, cfg);
            long t0 = System.currentTimeMillis();
            List<SearchHit> hits;
            try {
                long timeout = Math.min(SEARCH_TIMEOUT_MS, budget.remainingMs());
                hits = search.search(q, cfg, timeout);
            } catch (Exception e) {
                hits = List.of();                // provider 契约是不抛，这里双保险
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("搜索期间被取消");
            }
            if (!hits.isEmpty()) {
                out.put(t.source(), hits);
            }
            long elapsed = System.currentTimeMillis() - t0;
            trace.record(traceBuf, "SEARCH", code, q, digestHits(hits),
                    elapsed, hits.isEmpty() ? "EMPTY" : "OK", false);
            // LangSmith 明细：搜了什么、搜到了什么全文可见（本地摘要只带标题+网址）
            trace.stepFull(traceBuf, "搜索/" + code,
                    TraceRecorder.fields("核实术语", t.source(), "查询词", q),
                    TraceRecorder.fields("命中", hits.size() + " 条", "结果", renderHits(hits)),
                    elapsed, false);
            trace.flush(traceBuf);     // 搜完立刻可见：「模型正在联网」用户当场就能看到搜了哪些网页
        }
        return out;
    }

    /**
     * SEARCH 的本地摘要：带上命中标题与网址——运行现场要让用户看到搜了哪些网页。
     * 分隔用「 ｜ 」（两侧留空格），前端靠空白断出 URL 边界渲染成可点链接。
     */
    private static String digestHits(List<SearchHit> hits) {
        if (hits.isEmpty()) {
            return "0 条";
        }
        StringBuilder sb = new StringBuilder(hits.size() + " 条：");
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            if (i > 0) {
                sb.append(" ｜ ");
            }
            String title = h.title() == null ? "" : h.title().strip();
            if (title.length() > 40) {
                title = title.substring(0, 40) + "…";
            }
            sb.append(title).append(' ').append(h.url());
        }
        return sb.toString();
    }

    /** 搜索结果渲染成可读清单：标题 — 链接 + 摘要（LangSmith 上报用）。 */
    private static String renderHits(List<SearchHit> hits) {
        if (hits.isEmpty()) {
            return "（无命中）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            sb.append(i + 1).append(". ").append(h.title()).append(" — ").append(h.url());
            if (h.snippet() != null && !h.snippet().isBlank()) {
                sb.append("\n   ").append(h.snippet().strip().replaceAll("\\s+", " "));
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 搜索词 = 模型给的 query（没有就用原文）+ 档案的搜索提示词 + 目标语言。 */
    private String buildQuery(TermDraft t, SearchConfig cfg) {
        String base = t.queries().isEmpty() ? t.source() : t.queries().get(0);
        String hint = ctx.profile().getSearchHints();
        StringBuilder sb = new StringBuilder(base);
        if (hint != null && !hint.isBlank()) {
            String first = hint.split("[;；]")[0].trim();
            if (!first.isBlank() && !base.contains(first)) {
                sb.append(' ').append(first);
            }
        }
        return sb.toString();
    }

    // ─────────────────────── 状态机与部分结果 ───────────────────────

    /**
     * DAG 只能单调前进。回退直接抛异常——谁将来想加「再搜一次」的循环，
     * 会在开发期立刻炸出来，而不是在生产环境静默烧掉预算。
     */
    private void advance(Step next) {
        if (next.ordinal() <= step.ordinal()) {
            throw new IllegalStateException("DAG 不允许回退: " + step + " → " + next);
        }
        step = next;
    }

    /** 替换而非追加：步骤 C 精炼的是步骤 A 的同一批术语，追加会让每个词出现两次。 */
    private synchronized void commit(List<TermDraft> drafts) {
        committed.clear();
        if (drafts != null) {
            committed.addAll(drafts);
        }
    }

    private synchronized List<TermDraft> committed() {
        return new ArrayList<>(committed);
    }

    private SubAgentResult finish(Step terminal, DegradeReason why) {
        step = terminal;
        degrade = why;
        return snapshot();
    }

    /**
     * 线程安全快照。<b>可在 call() 被取消/中断之后由 orchestrator 调用</b>——
     * 这正是「超限→已有结果收尾」得以实现的机制。
     */
    public synchronized SubAgentResult snapshot() {
        List<TermDraft> terms = committed.stream().filter(TermDraft::valid).toList();
        SubAgentStatus st;
        if (step == Step.DONE && degrade == null) {
            st = SubAgentStatus.DONE;
        } else if (!terms.isEmpty()) {
            st = SubAgentStatus.PARTIAL;
        } else {
            st = SubAgentStatus.FAILED;
        }
        return new SubAgentResult(ctx.profile().getDomainCode(), st, terms, step, degrade,
                System.currentTimeMillis() - startedAt, tokens, proposalJson);
    }

    private static DegradeReason mapReason(String stopReason) {
        if (stopReason == null) {
            return DegradeReason.ERROR;
        }
        return switch (stopReason) {
            case "BUDGET" -> DegradeReason.BUDGET;
            case "TIMEOUT" -> DegradeReason.TIMEOUT;
            case "CANCELLED" -> DegradeReason.CANCELLED;
            default -> DegradeReason.ERROR;
        };
    }

    private static int countHits(Map<String, List<SearchHit>> hits) {
        return hits.values().stream().mapToInt(List::size).sum();
    }

    private static String summarize(JsonNode json) {
        if (json == null) {
            return "";
        }
        JsonNode terms = json.path("terms");
        if (terms.isArray()) {
            StringBuilder sb = new StringBuilder(terms.size() + " 词: ");
            int n = 0;
            for (JsonNode t : terms) {
                if (n++ >= 8) {
                    sb.append('…');
                    break;
                }
                sb.append(t.path("source").asText("")).append("→")
                        .append(t.path("target").asText("")).append("; ");
            }
            return sb.toString();
        }
        return json.toString();
    }

    /**
     * 用<b>实际检索结果</b>核验模型的权威声明，并把 searched 标记落到实处。
     *
     * <p>两件事一起做，因为它们共用同一个事实来源（本次真的搜到了什么）：
     * <ol>
     *   <li><b>核验 authorityUrl</b>：模型自报的链接必须出自我们检索到的结果且域名在
     *       权威白名单内，否则清空。没搜到权威来源的，策略里的 AUTHORITATIVE 也一并
     *       降级为 FREE——策略字段同样是 ⑤ 判定权威档的依据，只清 URL 拦不住。</li>
     *   <li><b>标记 searched</b>：搜过的词 authorityUrl 置 ""（有搜索无命中 = 该特征 0 分），
     *       <b>没搜过的置 null</b>（特征缺失，参与权重重归一）。二者在 ⑤ 里含义完全不同，
     *       混为一谈会让没配搜索的用户白白损失一档分数。</li>
     * </ol>
     */
    private List<TermDraft> verifyAuthority(List<TermDraft> drafts,
                                            Map<String, List<SearchHit>> hits) {
        SearchConfig cfg = ctx.searchCfg();
        List<TermDraft> out = new ArrayList<>(drafts.size());
        for (TermDraft d : drafts) {
            List<SearchHit> hs = hits.get(d.source());
            if (hs == null) {
                // 这个词没搜过：authorityUrl 必须是 null 而非 ""，否则 ⑤ 会当成「搜了没命中」
                out.add(d.withVerifiedAuthority(null, downgradeIfClaimed(d)));
                continue;
            }
            String verified = cfg.verifyAuthorityUrl(d.authorityUrl(), hs);
            out.add(verified.isBlank()
                    ? d.withVerifiedAuthority("", downgradeIfClaimed(d))
                    : d.withVerifiedAuthority(verified, d.strategy()));
        }
        return out;
    }

    /** 没有核验通过的权威来源，就不许挂 AUTHORITATIVE 策略。 */
    private static Strategy downgradeIfClaimed(TermDraft d) {
        return d.strategy() == Strategy.AUTHORITATIVE ? Strategy.FREE : d.strategy();
    }

    /** 一个子 Agent 的不可变输入。 */
    public record SubAgentContext(AgentProfile profile, String digest, String fullText,
                                  String sourceLang, String targetLang,
                                  LlmConfig subLlm, SearchConfig searchCfg, int maxSearchTerms) {
        public int digestChars() {
            return digest == null ? 0 : digest.length();
        }

        public SubAgentContext withProfile(AgentProfile p) {
            return new SubAgentContext(p, digest, fullText, sourceLang, targetLang,
                    subLlm, searchCfg, maxSearchTerms);
        }
    }
}
