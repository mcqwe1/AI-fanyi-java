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
            super(new AifanyiProperties(), new ObjectMapper());
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
}
