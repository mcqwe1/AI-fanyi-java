package com.aifanyi.agent.node;

import com.aifanyi.agent.model.TermDraft;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EvidenceMiner 单测：零 token 的证据挖掘与出现次数统计。
 * <p>出现次数是置信度里权重 0.35 的「跨分片一致性」特征来源，必须精确——
 * 它由代码在全文统计，不受喂给模型的摘要抽样影响。
 */
class EvidenceMinerTest {

    private static TermDraft t(String source) {
        return TermDraft.of(source, "", true, "medium", List.of(), "test");
    }

    // ---- 出现次数 ----

    @Test
    void countsOccurrencesCaseInsensitively() {
        String text = "API 是接口。调用 api 时要注意。API 的设计很重要。";
        assertEquals(3, EvidenceMiner.countOccurrences(text, "API"));
        assertEquals(3, EvidenceMiner.countOccurrences(text, "api"));
    }

    @Test
    void countsZeroWhenAbsent() {
        assertEquals(0, EvidenceMiner.countOccurrences("完全无关的文本", "Kubernetes"));
        assertEquals(0, EvidenceMiner.countOccurrences(null, "x"));
        assertEquals(0, EvidenceMiner.countOccurrences("text", ""));
    }

    /**
     * <b>词边界</b>（2026-08 修）：旧版直接数子串，"aa" 在 "aaaa" 里算 2 次。
     * 放到真实素材上就是：数 "API" 会把 rapid / capital 算进去，
     * 数 "AI" 会把 again / said / training / maintain 全算进去——
     * 英文素材里短词的次数虚高，直接污染 TermTriage 的收录判定。
     */
    @Test
    void latinTermsRequireWordBoundary() {
        assertEquals(0, EvidenceMiner.countOccurrences("aaaa", "aa"), "子串不再算出现");
        assertEquals(0, EvidenceMiner.countOccurrences(
                "It rained rapidly on the capital city.", "API"));
        assertEquals(0, EvidenceMiner.countOccurrences(
                "He said again that training helps maintain focus.", "AI"));

        // 真正作为独立词出现时正常计数，且大小写不敏感
        assertEquals(2, EvidenceMiner.countOccurrences("The API is fine. api again.", "API"));
        assertEquals(1, EvidenceMiner.countOccurrences("We use AI, not AIDS.", "AI"));
        // 标点紧邻不影响
        assertEquals(3, EvidenceMiner.countOccurrences("(API), API. \"API\"", "API"));
    }

    /** 含非单词字符的术语也要能数对（\\b 在这类词上会失效，故用 lookaround）。 */
    @Test
    void countsTermsWithPunctuation() {
        assertEquals(2, EvidenceMiner.countOccurrences("GPT-4 beats GPT-4 sometimes", "GPT-4"));
        assertEquals(1, EvidenceMiner.countOccurrences("built on .NET today", ".NET"));
        assertEquals(1, EvidenceMiner.countOccurrences("e-commerce is big", "e-commerce"));
    }

    /** 中日韩没有词间空格，词边界概念不适用，继续走子串计数。 */
    @Test
    void cjkStillCountsSubstrings() {
        assertEquals(2, EvidenceMiner.countOccurrences("情绪敏捷力很重要，情绪敏捷力可练", "情绪敏捷力"));
        assertEquals(2, EvidenceMiner.countOccurrences("这是茶道，那也是茶道", "茶道"));
    }

    // ---- 中文定义句 ----

    @Test
    void minesChineseIsPattern() {
        String text = "今天聊聊 CRISPR。CRISPR 是一种基因编辑技术，可以精确修改DNA序列。";
        List<String> hits = EvidenceMiner.mineOne(text, "CRISPR");
        assertFalse(hits.isEmpty(), "应挖到「X 是 ...」定义句");
        assertTrue(hits.get(0).contains("基因编辑"), "实际: " + hits.get(0));
    }

    @Test
    void minesChineseAliasPattern() {
        String text = "阿尔茨海默病，又称老年痴呆症，是常见的神经退行性疾病。";
        List<String> hits = EvidenceMiner.mineOne(text, "阿尔茨海默病");
        assertFalse(hits.isEmpty());
    }

    @Test
    void minesParentheticalGloss() {
        String text = "我们用到了 RAG（检索增强生成）这项技术。";
        List<String> hits = EvidenceMiner.mineOne(text, "RAG");
        assertFalse(hits.isEmpty(), "应挖到括号注释");
        assertTrue(hits.get(0).contains("检索增强生成"));
    }

    // ---- 英文定义句 ----

    @Test
    void minesEnglishIsPattern() {
        String text = "Let's talk about Kubernetes. Kubernetes is an open source container "
                + "orchestration platform used widely in production.";
        List<String> hits = EvidenceMiner.mineOne(text, "Kubernetes");
        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).toLowerCase().contains("container"));
    }

    @Test
    void minesEnglishStandsFor() {
        String text = "LLM stands for large language model in this context.";
        List<String> hits = EvidenceMiner.mineOne(text, "LLM");
        assertFalse(hits.isEmpty());
    }

    // ---- 日文定义句 ----

    @Test
    void minesJapaneseTohaPattern() {
        String text = "アニメとは日本発祥のアニメーション作品を指す言葉です。";
        List<String> hits = EvidenceMiner.mineOne(text, "アニメ");
        assertFalse(hits.isEmpty());
    }

    // ---- 边界 ----

    @Test
    void returnsEmptyWhenNoDefinition() {
        String text = "我们今天用了 Kubernetes 和 Docker 部署服务。";
        assertTrue(EvidenceMiner.mineOne(text, "Kubernetes").isEmpty(),
                "只是提及而非定义，不该挖出证据");
    }

    /** 术语含正则元字符时不能炸（Pattern.quote 兜住）。 */
    @Test
    void handlesRegexSpecialChars() {
        String text = "C++ 是一种编程语言。";
        assertDoesNotThrow(() -> EvidenceMiner.mineOne(text, "C++"));
        List<String> hits = EvidenceMiner.mineOne(text, "C++");
        assertFalse(hits.isEmpty(), "应能挖到含元字符术语的定义句");
    }

    @Test
    void mineBatchSkipsBlankSources() {
        Map<String, List<String>> out = EvidenceMiner.mine(
                "API 是应用程序接口。", List.of(t("API"), t("")));
        assertTrue(out.containsKey("API"));
        assertEquals(1, out.size());
    }

    @Test
    void mineHandlesNullInputs() {
        assertTrue(EvidenceMiner.mine(null, List.of(t("x"))).isEmpty());
        assertTrue(EvidenceMiner.mine("text", null).isEmpty());
        assertTrue(EvidenceMiner.mine("text", List.of()).isEmpty());
    }

    /** 每个术语最多挖 2 句，避免把整篇塞进 prompt。 */
    @Test
    void capsHitsPerTerm() {
        String text = "AI 是人工智能。AI 是未来趋势。AI 是重要技术。AI 是热门话题。";
        assertTrue(EvidenceMiner.mineOne(text, "AI").size() <= 2);
    }
}
