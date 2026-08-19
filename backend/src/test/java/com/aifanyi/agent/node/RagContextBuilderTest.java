package com.aifanyi.agent.node;

import com.aifanyi.agent.model.TermBundle;
import com.aifanyi.config.AifanyiProperties;
import com.aifanyi.llm.LlmConfig;
import com.aifanyi.llm.OpenAiTranslator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ⑧ 的未译行聚合：批内漏行与整批失败都必须以<b>全局行号</b>如实上报，
 * 这是「块状漏翻用户毫无感知」客诉的修复核心——译文可以降级，感知不能缺席。
 */
class RagContextBuilderTest {

    /** 可编排的假翻译器：默认逐行加前缀；可指定某行漏译、某批整个抛异常。 */
    static class FakeTranslator extends OpenAiTranslator {
        private final String failLine;      // 内容等于它的行「漏译」（保留原文并报 missing）
        private final String explodeLine;   // 批内含有它则整批抛异常

        FakeTranslator(String failLine, String explodeLine) {
            super(new AifanyiProperties(), new ObjectMapper(),
                    new com.aifanyi.llm.MtTranslateClient(new ObjectMapper()));
            this.failLine = failLine;
            this.explodeLine = explodeLine;
        }

        @Override
        public BatchOutcome translateBatchWithContext(List<String> batch, String targetLang,
                                                      LlmConfig cfg, Map<String, String> glossary,
                                                      String stylePrompt, List<String> priorSource,
                                                      List<String> priorTarget) {
            if (explodeLine != null && batch.contains(explodeLine)) {
                throw new RuntimeException("模拟接口失败");
            }
            List<String> out = new ArrayList<>(batch.size());
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                if (batch.get(i).equals(failLine)) {
                    out.add(batch.get(i));          // 漏译 = 保留原文
                    missing.add(i);
                } else {
                    out.add("译:" + batch.get(i));
                }
            }
            return new BatchOutcome(out, missing);
        }
    }

    private static LlmConfig cfg(int batchSize) {
        return new LlmConfig("http://x", "key", "test-model", false, batchSize, 4);
    }

    private static List<String> sources(int n) {
        List<String> s = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            s.add("s" + i);
        }
        return s;
    }

    @Test
    void 批内漏行换算成全局行号() {
        // batchSize=3：s4 位于第 1 批（批内下标 1）→ 全局 4
        RagContextBuilder rag = new RagContextBuilder(
                new FakeTranslator("s4", null), new AifanyiProperties());
        RagContextBuilder.RagResult r = rag.translate(
                sources(10), "中文", cfg(3), TermBundle.empty(), null, null);

        assertEquals(10, r.targets().size());
        assertEquals("s4", r.targets().get(4));            // 漏行保留原文
        assertEquals("译:s3", r.targets().get(3));          // 其余正常
        assertEquals(List.of(4), r.missingLines());
    }

    @Test
    void 整批失败按整批行号上报() {
        // batchSize=3：s9 独占最后一批（第 3 批）→ 全局 9
        RagContextBuilder rag = new RagContextBuilder(
                new FakeTranslator(null, "s9"), new AifanyiProperties());
        RagContextBuilder.RagResult r = rag.translate(
                sources(10), "中文", cfg(3), TermBundle.empty(), null, null);

        assertEquals(10, r.targets().size());
        assertEquals("s9", r.targets().get(9));
        assertEquals(List.of(9), r.missingLines());
    }

    @Test
    void 全部成功时未译清单为空且顺序无损() {
        RagContextBuilder rag = new RagContextBuilder(
                new FakeTranslator(null, null), new AifanyiProperties());
        RagContextBuilder.RagResult r = rag.translate(
                sources(7), "中文", cfg(2), TermBundle.empty(), null, null);

        assertTrue(r.missingLines().isEmpty());
        for (int i = 0; i < 7; i++) {
            assertEquals("译:s" + i, r.targets().get(i));   // 组间并发下顺序仍与原文一致
        }
    }

    // ────────── 滚动窗口档位（2026-08 提速改造） ──────────

    /** 记录每一批实际收到的上下文，用来验证「有没有制造串行依赖」。 */
    static class SpyTranslator extends OpenAiTranslator {
        record Seen(String firstLine, List<String> priorSrc, List<String> priorTgt) {
        }

        final List<Seen> seen = java.util.Collections.synchronizedList(new ArrayList<>());

        SpyTranslator() {
            super(new AifanyiProperties(), new ObjectMapper(),
                    new com.aifanyi.llm.MtTranslateClient(new ObjectMapper()));
        }

        @Override
        public BatchOutcome translateBatchWithContext(List<String> batch, String targetLang,
                                                      LlmConfig cfg, Map<String, String> glossary,
                                                      String stylePrompt, List<String> priorSource,
                                                      List<String> priorTarget) {
            seen.add(new Seen(batch.get(0), priorSource, priorTarget));
            List<String> out = new ArrayList<>(batch.size());
            for (String s : batch) {
                out.add("译:" + s);
            }
            return new BatchOutcome(out, List.of());
        }
    }

    private static AifanyiProperties propsWith(String contextMode) {
        AifanyiProperties p = new AifanyiProperties();
        p.getAgent().setContextMode(contextMode);
        p.getAgent().setContextOverlap(2);
        return p;
    }

    @Test
    void 默认档只传前文原文不传前文译文() {
        // 这是提速的关键：前文译文是上一批的「输出」，传它就等于要求串行；
        // 前文原文是上一批的「输入」，开跑前就已知，可以全并发。
        SpyTranslator spy = new SpyTranslator();
        RagContextBuilder rag = new RagContextBuilder(spy, propsWith("source"));
        rag.translate(sources(10), "中文", cfg(3), TermBundle.empty(), null, null);

        assertEquals(4, spy.seen.size());                       // 10 行 / 每批 3 = 4 批
        for (SpyTranslator.Seen s : spy.seen) {
            assertNull(s.priorTgt(), "source 档绝不能传前文译文，否则又退回串行");
        }
        // 除首批外，每批都拿到了上一批末尾的原文作上下文
        SpyTranslator.Seen first = spy.seen.stream()
                .filter(x -> x.firstLine().equals("s0")).findFirst().orElseThrow();
        assertNull(first.priorSrc());
        SpyTranslator.Seen second = spy.seen.stream()
                .filter(x -> x.firstLine().equals("s3")).findFirst().orElseThrow();
        assertEquals(List.of("s1", "s2"), second.priorSrc());   // overlap=2
    }

    @Test
    void full档传前文原文加译文() {
        SpyTranslator spy = new SpyTranslator();
        // 并发压到 1 → 只有一组，组内严格串行，能稳定观察到译文被往下传
        RagContextBuilder rag = new RagContextBuilder(spy, propsWith("full"));
        LlmConfig oneWay = new LlmConfig("http://x", "key", "test-model",
                LlmConfig.PROTO_OPENAI, 60, false, 3, 1);
        rag.translate(sources(9), "中文", oneWay, TermBundle.empty(), null, null);

        assertEquals(3, spy.seen.size());
        assertNull(spy.seen.get(0).priorTgt());                 // 首批没有前文
        assertEquals(List.of("译:s1", "译:s2"), spy.seen.get(1).priorTgt());
        assertEquals(List.of("s1", "s2"), spy.seen.get(1).priorSrc());
    }

    @Test
    void 组边界不再丢上下文() {
        // 旧实现固定 5 批一组，每组第一批的 priorSrc 是 null——组边界是硬断点。
        // 现在每组第一批会带上「全局上一批」的原文。
        SpyTranslator spy = new SpyTranslator();
        RagContextBuilder rag = new RagContextBuilder(spy, propsWith("full"));
        LlmConfig fourWay = new LlmConfig("http://x", "key", "test-model",
                LlmConfig.PROTO_OPENAI, 60, false, 3, 4);
        rag.translate(sources(12), "中文", fourWay, TermBundle.empty(), null, null);

        assertEquals(4, spy.seen.size());
        long withoutContext = spy.seen.stream().filter(s -> s.priorSrc() == null).count();
        assertEquals(1, withoutContext, "只有全片第一批可以没有上下文");
    }
}
