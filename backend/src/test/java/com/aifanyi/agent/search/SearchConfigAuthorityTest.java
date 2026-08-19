package com.aifanyi.agent.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 权威来源核验单测。
 *
 * <p><b>为什么这块值得穷尽测试</b>：它是分诊 A 档（权威佐证）的唯一入口，
 * 而 A 档意味着自动入库并直接启用。模型完全有能力编一个看着像模像样的
 * wikipedia 链接，若采信，「不认模型自报」这条底线就从另一个字段被绕过去了。
 */
class SearchConfigAuthorityTest {

    private static SearchConfig cfg() {
        return new SearchConfig("tavily", SearchEngines.Wire.TAVILY, "http://x", "k", 5,
                List.of("wikipedia.org", "kubernetes.io", "nih.gov"));
    }

    /** 默认命中的结果都谈到了 Kubernetes（核验的第二道闸要求结果确实提到该词）。 */
    private static SearchHit hit(String url) {
        return SearchHit.of("Kubernetes 文档", url, "Kubernetes 是一个容器编排系统");
    }

    /** 域名权威、但内容跟要查的词毫无关系——搜索引擎返回的旁支结果。 */
    private static SearchHit offTopic(String url) {
        return SearchHit.of("今日天气", url, "本周多云转晴，气温回升");
    }

    private static String verify(String claimed, java.util.List<SearchHit> hits) {
        return cfg().verifyAuthorityUrl(claimed, hits, "Kubernetes", "K8s");
    }

    /** <b>核心</b>：模型编造的链接，即便域名在白名单里，没真搜到就不算数。 */
    @Test
    void rejectsHallucinatedUrlNotInActualHits() {
        List<SearchHit> actual = List.of(hit("https://random-blog.com/post"));
        String v = verify("https://en.wikipedia.org/wiki/Kubernetes", actual);
        assertEquals("", v, "白名单域名 + 没真搜到 = 不认，否则模型能凭空拿满分天花板");
    }

    /** 完全没搜到东西时，任何自报都不算数。 */
    @Test
    void rejectsAnyClaimWhenNoHits() {
        assertEquals("", verify("https://wikipedia.org/x", List.of()));
        assertEquals("", verify("https://wikipedia.org/x", null));
    }

    /** 模型给的链接确实在检索结果里且域名权威 → 认，且返回实际抓到的 URL。 */
    @Test
    void acceptsClaimBackedByActualHit() {
        List<SearchHit> actual = List.of(
                hit("https://random-blog.com/post"),
                hit("https://kubernetes.io/zh/docs/concepts/"));
        String v = verify("https://kubernetes.io/zh/", actual);
        assertEquals("https://kubernetes.io/zh/docs/concepts/", v,
                "返回实际检索到的 URL，而不是模型写的那个（模型可能把路径记串）");
    }

    /** 模型没给链接，但检索结果里本就含权威来源 → 仍算命中（证据确实喂给它了）。 */
    @Test
    void acceptsAuthorityPresentInHitsWithoutClaim() {
        List<SearchHit> actual = List.of(hit("https://blog.com/x"), hit("https://nih.gov/study/1"));
        assertEquals("https://nih.gov/study/1", verify(null, actual));
        assertEquals("https://nih.gov/study/1", verify("", actual));
    }

    /** 搜到了但全是非权威来源 → 空串（⑤ 按「搜了没命中」计 0 分，不是特征缺失）。 */
    @Test
    void returnsEmptyWhenHitsAreAllNonAuthoritative() {
        List<SearchHit> actual = List.of(hit("https://blog.com/x"), hit("https://forum.net/y"));
        assertEquals("", verify("https://blog.com/x", actual));
    }

    /** 子域名要能命中（zh.wikipedia.org 属于 wikipedia.org）。 */
    @Test
    void matchesSubdomains() {
        assertTrue(cfg().isAuthority("zh.wikipedia.org"));
        assertTrue(cfg().isAuthority("WIKIPEDIA.ORG"), "大小写不敏感");
        assertFalse(cfg().isAuthority("notwikipedia.org"), "不能被后缀欺骗");
        assertFalse(cfg().isAuthority("wikipedia.org.evil.com"), "不能被前缀欺骗");
        assertFalse(cfg().isAuthority(""));
        assertFalse(cfg().isAuthority(null));
    }

    /** 不联网配置下没有白名单，一律不认权威。 */
    @Test
    void noneConfigNeverAuthoritative() {
        assertFalse(SearchConfig.none().isAuthority("wikipedia.org"));
        assertEquals("", SearchConfig.none().verifyAuthorityUrl(
                "https://wikipedia.org/x", List.of(hit("https://wikipedia.org/x")), "Kubernetes", "K8s"));
    }

    /**
     * <b>第二道闸</b>：域名在白名单里，但那条结果根本没谈到这个词 → 不算命中。
     * 旧版只要检索结果里出现任意白名单域名就算数，等于「搜到过维基百科」即可兑换最高档。
     */
    @Test
    void rejectsAuthorityDomainWhoseContentIsOffTopic() {
        List<SearchHit> actual = List.of(offTopic("https://zh.wikipedia.org/wiki/Weather"));
        assertEquals("", verify(null, actual), "权威域名 + 内容无关 = 不是这个词的证据");
        assertEquals("", verify("https://zh.wikipedia.org/wiki/Weather", actual),
                "模型自报同一条也不行");
    }

    /** 译文命中也算：中文权威页可能只写译名不写原文。 */
    @Test
    void acceptsWhenOnlyTargetTermAppears() {
        SearchHit zh = SearchHit.of("库伯内提斯", "https://zh.wikipedia.org/wiki/K8s", "容器编排");
        assertEquals("https://zh.wikipedia.org/wiki/K8s",
                cfg().verifyAuthorityUrl(null, List.of(zh), "Kubernetes", "库伯内提斯"));
    }
}
