package com.aifanyi.agent.node;

import com.aifanyi.agent.AgentBudget;
import com.aifanyi.agent.Deadline;
import com.aifanyi.agent.llm.AgentJsonClient;
import com.aifanyi.agent.model.*;
import com.aifanyi.agent.profile.AgentProfile;
import com.aifanyi.agent.search.SearchConfig;
import com.aifanyi.agent.search.SearchHit;
import com.aifanyi.agent.search.SearchProvider;
import com.aifanyi.agent.trace.TraceRecorder;
import com.aifanyi.llm.LlmConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SubAgentTask 单测：DAG 的不变式与降级契约。
 * <p>这些是 harness「约束与恢复」层的核心保证，一旦破坏就会在生产环境静默烧预算，
 * 故用可控的假 LLM 把每条路径都跑一遍。
 */
class SubAgentTaskTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private TraceRecorder trace;
    private TraceRecorder.Buffer buf;

    @BeforeEach
    void setUp() {
        trace = new TraceRecorder(null, null);  // mapper/langSmith 为 null：record 内部吞掉，观测缺席不影响被测逻辑
        buf = trace.newBuffer(1L);
    }

    // ─────────────────── 测试替身 ───────────────────

    /** 可编排的假 LLM：按调用顺序返回预设响应，可注入延迟。 */
    static class FakeLlm extends AgentJsonClient {
        private final ObjectMapper m;
        private final List<String> responses;
        private final long delayMs;
        int calls;

        FakeLlm(ObjectMapper m, List<String> responses, long delayMs) {
            super(m, null);
            this.m = m;
            this.responses = responses;
            this.delayMs = delayMs;
        }

        @Override
        public Call callJson(Prompt prompt, LlmConfig cfg, AgentBudget budget, String tag) {
            if (!budget.tryLlmStep()) {
                return new Call(null, 0, 0, 0, 0, "BUDGET");
            }
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Call(null, delayMs, 0, 0, 0, "CANCELLED");
                }
            }
            int i = calls++;
            if (i >= responses.size() || responses.get(i) == null) {
                return new Call(null, 1, 0, 0, 0, "PARSE_FAIL");
            }
            try {
                return new Call(m.readTree(responses.get(i)), 1, 10, 10, 20, "OK");
            } catch (Exception e) {
                return new Call(null, 1, 0, 0, 0, "PARSE_FAIL");
            }
        }
    }

    static class FakeSearch implements SearchProvider {
        int calls;
        private final List<SearchHit> hits;

        FakeSearch(List<SearchHit> hits) {
            this.hits = hits;
        }

        @Override
        public String name() {
            return "FAKE";
        }

        @Override
        public List<SearchHit> search(String query, SearchConfig cfg, long timeoutMs) {
            calls++;
            return hits;
        }
    }

    private static AgentProfile profile() {
        AgentProfile p = new AgentProfile();
        p.setDomainCode("it");
        p.setName("IT");
        p.setConventions("API 保留原文");
        p.setSearchHints("官方文档");
        return p;
    }

    private SubAgentTask.SubAgentContext ctx(SearchConfig sc) {
        return new SubAgentTask.SubAgentContext(profile(),
                "Kubernetes 是容器编排平台。我们用 API 调用它。",
                "Kubernetes 是容器编排平台。我们用 API 调用它。Kubernetes 很流行。",
                "en", "中文",
                new LlmConfig("http://x", "k", "m", false, 40, 8),
                sc, 3);
    }

    private static AgentBudget budget(long ms) {
        return AgentBudget.of(Deadline.in(Duration.ofMillis(ms)), 2, 3, 2);
    }

    private static final String EXTRACT_NO_SEARCH = """
            {"status":"DONE","terms":[
              {"source":"Kubernetes","target":"Kubernetes","needSearch":false,"confidence":"high"},
              {"source":"API","target":"API","needSearch":false,"confidence":"high"}]}""";

    private static final String EXTRACT_NEED_SEARCH = """
            {"status":"NEED_SEARCH","terms":[
              {"source":"Kubernetes","target":"K8s","needSearch":true,"confidence":"low",
               "queries":["Kubernetes 中文译名"]}]}""";

    private static final String RESOLVE_OK = """
            {"terms":[{"source":"Kubernetes","target":"Kubernetes","strategy":"KEEP_ORIGINAL",
             "evidence":"官方保留原名","confidence":"high","reason":"业界通用"}],
             "profileProposal":{"addSearchHints":["官方中文文档"]}}""";

    // ─────────────────── 正常路径 ───────────────────

    /** 不需联网时只花 1 次 LLM，直接终稿——省掉步骤 C 的往返。 */
    @Test
    void skipsSearchAndResolveWhenNotNeeded() {
        FakeLlm llm = new FakeLlm(mapper, List.of(EXTRACT_NO_SEARCH), 0);
        FakeSearch search = new FakeSearch(List.of());
        SubAgentTask task = new SubAgentTask(ctx(SearchConfig.none()), budget(60_000),
                llm, search, trace, buf);

        SubAgentResult r = task.call();
        assertEquals(SubAgentStatus.DONE, r.status());
        assertEquals(2, r.terms().size());
        assertEquals(1, llm.calls, "不需联网时只应调用 1 次 LLM");
        assertEquals(0, search.calls);
        assertEquals(Step.DONE, r.lastStep());
    }

    /** 出现次数由代码在全文统计，不是模型自报。 */
    @Test
    void countsOccurrencesFromFullText() {
        FakeLlm llm = new FakeLlm(mapper, List.of(EXTRACT_NO_SEARCH), 0);
        SubAgentResult r = new SubAgentTask(ctx(SearchConfig.none()), budget(60_000),
                llm, new FakeSearch(List.of()), trace, buf).call();
        TermDraft k8s = r.terms().stream()
                .filter(t -> t.source().equals("Kubernetes")).findFirst().orElseThrow();
        assertEquals(2, k8s.occurrences(), "全文出现 2 次");
    }

    /** 完整路径：提取 → 搜索 → 定译。 */
    @Test
    void fullPathWithSearch() {
        FakeLlm llm = new FakeLlm(mapper, List.of(EXTRACT_NEED_SEARCH, RESOLVE_OK), 0);
        FakeSearch search = new FakeSearch(List.of(
                SearchHit.of("K8s 官方", "https://kubernetes.io/zh/", "Kubernetes 官方中文站")));
        SearchConfig sc = new SearchConfig("fake", com.aifanyi.agent.search.SearchEngines.Wire.TAVILY,
                "http://x", "k", 5, List.of("kubernetes.io"));

        SubAgentResult r = new SubAgentTask(ctx(sc), budget(60_000), llm, search, trace, buf).call();
        assertEquals(SubAgentStatus.DONE, r.status());
        assertEquals(2, llm.calls);
        assertEquals(1, search.calls);
        assertEquals(Strategy.KEEP_ORIGINAL, r.terms().get(0).strategy());
        assertNotNull(r.profileProposalJson(), "档案修正提议应被带回（④→⑥ 回路）");
    }

    // ─────────────────── 降级路径 ───────────────────

    /** 步骤 A 失败 = 真正的空结果。 */
    @Test
    void extractFailureYieldsEmptyResult() {
        FakeLlm llm = new FakeLlm(mapper, java.util.Collections.singletonList(null), 0);
        SubAgentResult r = new SubAgentTask(ctx(SearchConfig.none()), budget(60_000),
                llm, new FakeSearch(List.of()), trace, buf).call();
        assertEquals(SubAgentStatus.FAILED, r.status());
        assertTrue(r.terms().isEmpty());
    }

    /** <b>核心契约</b>：步骤 C 失败时保住步骤 A 的成果，而非返回空。 */
    @Test
    void resolveFailureKeepsPartialResults() {
        FakeLlm llm = new FakeLlm(mapper, java.util.Arrays.asList(EXTRACT_NEED_SEARCH, null), 0);
        SearchConfig sc = new SearchConfig("fake", com.aifanyi.agent.search.SearchEngines.Wire.TAVILY,
                "http://x", "k", 5, List.of());
        SubAgentResult r = new SubAgentTask(ctx(sc), budget(60_000),
                llm, new FakeSearch(List.of()), trace, buf).call();

        assertEquals(SubAgentStatus.PARTIAL, r.status(), "定译失败但候选还在 → 部分结果");
        assertEquals(1, r.terms().size(), "步骤 A 的候选必须保住");
        assertEquals("Kubernetes", r.terms().get(0).source());
        assertEquals(Step.WRAPPED_UP, r.lastStep());
    }

    /** 预算不足以跑完搜索+定译时提前收尾，不白烧请求。 */
    @Test
    void wrapsUpWhenBudgetTooTightForSearchPhase() {
        FakeLlm llm = new FakeLlm(mapper, List.of(EXTRACT_NEED_SEARCH), 0);
        FakeSearch search = new FakeSearch(List.of());
        // 只给 3 秒：够跑步骤 A，但不够 STEP_C_RESERVE_MS(10s)
        SubAgentResult r = new SubAgentTask(ctx(SearchConfig.none()), budget(3_000),
                llm, search, trace, buf).call();

        assertEquals(SubAgentStatus.PARTIAL, r.status());
        assertEquals(1, r.terms().size(), "已有结果必须收尾带回");
        assertEquals(DegradeReason.BUDGET, r.degradeReason());
        assertEquals(0, search.calls, "预算不足时不该发起搜索");
    }

    /** 搜索不可用（未配置）是正常降级，不是错误——仍走完 DAG。 */
    @Test
    void searchUnavailableIsNormalDegradation() {
        FakeLlm llm = new FakeLlm(mapper, List.of(EXTRACT_NEED_SEARCH, RESOLVE_OK), 0);
        FakeSearch search = new FakeSearch(List.of());
        SubAgentResult r = new SubAgentTask(ctx(SearchConfig.none()), budget(60_000),
                llm, search, trace, buf).call();

        assertEquals(SubAgentStatus.DONE, r.status(), "无搜索仍能正常完成");
        assertEquals(0, search.calls, "未配置搜索时不该调用 provider");
        assertEquals(2, llm.calls, "仍要走步骤 C 定译");
    }

    // ─────────────────── 取消与部分结果 ───────────────────

    /**
     * <b>最关键的一条</b>：Future 被取消后，仍能通过 snapshot() 取回已提交的部分结果。
     * 这是「超限→已有结果收尾」得以实现的机制。
     */
    @Test
    void snapshotSurvivesCancellation() throws Exception {
        // 步骤 A 秒回，步骤 C 卡住 → 主线程超时取消
        FakeLlm llm = new FakeLlm(mapper, java.util.Arrays.asList(EXTRACT_NEED_SEARCH, RESOLVE_OK), 5_000) {
            @Override
            public Call callJson(Prompt p, LlmConfig c, AgentBudget b, String tag) {
                if (calls == 0) {          // 步骤 A 立即返回
                    calls++;
                    b.tryLlmStep();
                    try {
                        return new Call(mapper.readTree(EXTRACT_NEED_SEARCH), 1, 0, 0, 0, "OK");
                    } catch (Exception e) {
                        return new Call(null, 1, 0, 0, 0, "PARSE_FAIL");
                    }
                }
                return super.callJson(p, c, b, tag);   // 步骤 C 睡 5 秒
            }
        };
        SearchConfig sc = new SearchConfig("fake", com.aifanyi.agent.search.SearchEngines.Wire.TAVILY,
                "http://x", "k", 5, List.of());
        SubAgentTask task = new SubAgentTask(ctx(sc), budget(60_000),
                llm, new FakeSearch(List.of()), trace, buf);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<SubAgentResult> f = pool.submit(task);
        assertThrows(TimeoutException.class, () -> f.get(600, TimeUnit.MILLISECONDS));
        f.cancel(true);

        SubAgentResult snap = task.snapshot().degradedBy(DegradeReason.TIMEOUT);
        assertEquals(SubAgentStatus.PARTIAL, snap.status());
        assertEquals(1, snap.terms().size(), "取消后仍能取回步骤 A 已提交的候选");
        assertEquals(DegradeReason.TIMEOUT, snap.degradeReason());
        pool.shutdownNow();
    }

    /** call() 永不抛异常——这是 orchestrator 只需处理 TimeoutException 的前提。 */
    @Test
    void callNeverThrows() {
        AgentJsonClient boom = new AgentJsonClient(mapper, null) {
            @Override
            public Call callJson(Prompt p, LlmConfig c, AgentBudget b, String tag) {
                throw new RuntimeException("模拟内部炸裂");
            }
        };
        SubAgentTask task = new SubAgentTask(ctx(SearchConfig.none()), budget(60_000),
                boom, new FakeSearch(List.of()), trace, buf);
        SubAgentResult r = assertDoesNotThrow(task::call);
        assertEquals(SubAgentStatus.FAILED, r.status());
        assertEquals(DegradeReason.ERROR, r.degradeReason());
    }

    /** 搜索次数受预算硬约束（≤2），哪怕有更多词需要核实。 */
    @Test
    void searchCountIsCappedByBudget() {
        String manyTerms = """
                {"status":"NEED_SEARCH","terms":[
                  {"source":"Kubernetes","target":"K","needSearch":true,"queries":["a"]},
                  {"source":"API","target":"A","needSearch":true,"queries":["b"]},
                  {"source":"Docker","target":"D","needSearch":true,"queries":["c"]}]}""";
        FakeLlm llm = new FakeLlm(mapper, List.of(manyTerms, RESOLVE_OK), 0);
        FakeSearch search = new FakeSearch(List.of(SearchHit.of("t", "https://x.com/p", "s")));
        SearchConfig sc = new SearchConfig("fake", com.aifanyi.agent.search.SearchEngines.Wire.TAVILY,
                "http://x", "k", 5, List.of());

        new SubAgentTask(ctx(sc), budget(60_000), llm, search, trace, buf).call();
        assertEquals(2, search.calls, "3 个词需核实，但搜索预算只允许 2 次");
    }

    // ─────────────────── 权威来源核验（防「自报当证据」）───────────────────

    /** 模型自报 AUTHORITATIVE + 编造链接的响应。 */
    private static final String RESOLVE_FAKE_AUTHORITY = """
            {"terms":[{"source":"Kubernetes","target":"库伯内特斯","strategy":"AUTHORITATIVE",
             "evidence":"官方译名","authorityUrl":"https://zh.wikipedia.org/wiki/Kubernetes",
             "confidence":"high","reason":"维基百科"}]}""";

    /**
     * <b>最关键的一条</b>：模型编造权威链接（我们实际只搜到无关博客）时，
     * 必须被清掉——否则它凭空拿到 0.95 天花板并自动 VERIFIED 进用户术语库。
     */
    @Test
    void fabricatedAuthorityUrlIsStripped() {
        FakeLlm llm = new FakeLlm(mapper, List.of(EXTRACT_NEED_SEARCH, RESOLVE_FAKE_AUTHORITY), 0);
        // 实际搜到的是无关博客，并没有维基百科
        FakeSearch search = new FakeSearch(List.of(
                SearchHit.of("某博客", "https://random-blog.com/post", "随便写的")));
        SearchConfig sc = new SearchConfig("fake", com.aifanyi.agent.search.SearchEngines.Wire.TAVILY,
                "http://x", "k", 5, List.of("wikipedia.org", "kubernetes.io"));

        SubAgentResult r = new SubAgentTask(ctx(sc), budget(60_000), llm, search, trace, buf).call();
        TermDraft t = r.terms().get(0);

        assertFalse(t.hasAuthority(), "编造的权威链接必须被核验掉");
        assertEquals("", t.authorityUrl(), "搜过但没命中权威 → 空串（不是 null）");
        assertNotEquals(Strategy.AUTHORITATIVE, t.strategy(),
                "没有核验通过的权威来源，策略也不许挂 AUTHORITATIVE");
    }

    /** 模型给的链接确实在检索结果里 → 认，保住真实的权威判定能力。 */
    @Test
    void genuineAuthorityUrlIsKept() {
        FakeLlm llm = new FakeLlm(mapper, List.of(EXTRACT_NEED_SEARCH, RESOLVE_FAKE_AUTHORITY), 0);
        FakeSearch search = new FakeSearch(List.of(
                SearchHit.of("维基", "https://zh.wikipedia.org/wiki/Kubernetes", "容器编排")));
        SearchConfig sc = new SearchConfig("fake", com.aifanyi.agent.search.SearchEngines.Wire.TAVILY,
                "http://x", "k", 5, List.of("wikipedia.org"));

        SubAgentResult r = new SubAgentTask(ctx(sc), budget(60_000), llm, search, trace, buf).call();
        TermDraft t = r.terms().get(0);

        assertTrue(t.hasAuthority(), "真实搜到的权威来源必须保留");
        assertEquals(Strategy.AUTHORITATIVE, t.strategy());
    }

    /**
     * 没配搜索时 authorityUrl 必须是 <b>null 而非空串</b>。
     * 二者在 ⑤ 里含义完全不同：null=特征缺失参与重归一，""=搜了没命中按 0 分计。
     * 混为一谈会让没配搜索的用户每个词都白丢一档分数、术语被静默降到不落库。
     */
    @Test
    void unsearchedTermsKeepNullAuthorityUrl() {
        FakeLlm llm = new FakeLlm(mapper, List.of(EXTRACT_NEED_SEARCH, RESOLVE_OK), 0);
        SubAgentResult r = new SubAgentTask(ctx(SearchConfig.none()), budget(60_000),
                llm, new FakeSearch(List.of()), trace, buf).call();

        TermDraft t = r.terms().get(0);
        assertNull(t.authorityUrl(), "没搜过 → null（特征缺失），不能是空串");
    }
}
