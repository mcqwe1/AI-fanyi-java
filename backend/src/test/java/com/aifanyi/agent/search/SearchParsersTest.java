package com.aifanyi.agent.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SearchParsers 单测：各家响应格式的容错解析。
 *
 * <p>这块最容易出<b>静默故障</b>——解析不出结果时上游按「无答案」正常降级，
 * 于是用户配好了 Key 也永远走不联网分支，日志里还看不出异常。
 * 故这里把三家的真实结构与常见变体都钉住。
 */
class SearchParsersTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<SearchHit> parse(String json, int topK) throws Exception {
        return SearchParsers.parse(mapper.readTree(json), topK, "test", json);
    }

    /** Tavily：results[] + content 字段。 */
    @Test
    void parsesTavilyShape() throws Exception {
        String body = """
                {"query":"k8s","results":[
                  {"title":"Kubernetes 官方","url":"https://kubernetes.io/zh/",
                   "content":"Kubernetes 是容器编排平台","score":0.98},
                  {"title":"维基","url":"https://zh.wikipedia.org/wiki/K8s","content":"简称 K8s"}]}""";
        List<SearchHit> hits = parse(body, 5);
        assertEquals(2, hits.size());
        assertEquals("Kubernetes 官方", hits.get(0).title());
        assertEquals("https://kubernetes.io/zh/", hits.get(0).url());
        assertEquals("Kubernetes 是容器编排平台", hits.get(0).snippet());
        assertEquals("kubernetes.io", hits.get(0).domain(), "域名要能抽出来，权威判定全靠它");
    }

    /** Serper：organic[] + link/snippet 字段名与 Tavily 完全不同。 */
    @Test
    void parsesSerperShape() throws Exception {
        String body = """
                {"searchParameters":{"q":"k8s"},"organic":[
                  {"title":"Kubernetes","link":"https://kubernetes.io/","snippet":"容器编排","position":1},
                  {"title":"Docker","link":"https://docker.com/","snippet":"容器引擎","position":2}]}""";
        List<SearchHit> hits = parse(body, 5);
        assertEquals(2, hits.size());
        assertEquals("https://kubernetes.io/", hits.get(0).url());
        assertEquals("容器编排", hits.get(0).snippet());
    }

    /** 博查：data.webPages.value[] 三层嵌套（Bing 风格）。 */
    @Test
    void parsesBochaShape() throws Exception {
        String body = """
                {"code":200,"data":{"webPages":{"totalEstimatedMatches":1000,"value":[
                  {"name":"阿尔茨海默病","url":"https://baike.baidu.com/item/x",
                   "snippet":"一种神经退行性疾病","summary":"详细说明"}]}}}""";
        List<SearchHit> hits = parse(body, 5);
        assertEquals(1, hits.size());
        assertEquals("阿尔茨海默病", hits.get(0).title(), "博查用 name 而非 title");
        assertEquals("baike.baidu.com", hits.get(0).domain());
    }

    /** topK 必须截断——搜索正文直接进 prompt，不截会挤爆上下文预算。 */
    @Test
    void respectsTopK() throws Exception {
        String body = """
                {"results":[{"url":"https://a.com","content":"1"},{"url":"https://b.com","content":"2"},
                  {"url":"https://c.com","content":"3"},{"url":"https://d.com","content":"4"}]}""";
        assertEquals(2, parse(body, 2).size());
        assertEquals(0, parse(body, 0).size());
    }

    /** 超长正文要截断，否则单条就能顶掉整个摘要预算。 */
    @Test
    void clampsOverlongSnippet() throws Exception {
        String longText = "字".repeat(2000);
        List<SearchHit> hits = parse(
                "{\"results\":[{\"url\":\"https://a.com\",\"content\":\"" + longText + "\"}]}", 5);
        assertTrue(hits.get(0).snippet().length() < 600, "超长正文必须截断");
    }

    /** 空结果、错误响应、非预期结构都返回空列表，绝不抛异常。 */
    @Test
    void neverThrowsOnUnexpectedShapes() throws Exception {
        assertTrue(parse("{\"results\":[]}", 5).isEmpty());
        assertTrue(parse("{\"error\":\"invalid api key\"}", 5).isEmpty());
        assertTrue(parse("{}", 5).isEmpty());
        assertTrue(SearchParsers.parse(null, 5, "test", null).isEmpty());
    }

    /** 既无摘要又无链接的条目没有判定价值，跳过而不是塞个空壳进去。 */
    @Test
    void skipsValuelessEntries() throws Exception {
        String body = """
                {"results":[{"title":"只有标题"},
                  {"title":"正常","url":"https://a.com","content":"有内容"}]}""";
        List<SearchHit> hits = parse(body, 5);
        assertEquals(1, hits.size());
        assertEquals("正常", hits.get(0).title());
    }
}
