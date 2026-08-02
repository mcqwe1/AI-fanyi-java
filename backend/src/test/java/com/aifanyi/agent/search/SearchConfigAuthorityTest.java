package com.aifanyi.agent.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 权威来源核验单测。
 *
 * <p><b>为什么这块值得穷尽测试</b>：它是 ⑤ 置信度 0.95 天花板的唯一入口，
 * 而 0.95 天花板意味着能越过 0.85 自动 VERIFIED 写进用户术语库。
 * 模型完全有能力在步骤 C 里编一个看着像模像样的 wikipedia 链接，
 * 若采信，ConfidenceScorer 辛苦设的天花板就从另一个字段被绕过去了。
 */
class SearchConfigAuthorityTest {

    private static SearchConfig cfg() {
        return new SearchConfig("tavily", SearchEngines.Wire.TAVILY, "http://x", "k", 5,
                List.of("wikipedia.org", "kubernetes.io", "nih.gov"));
    }

    private static SearchHit hit(String url) {
        return SearchHit.of("t", url, "snippet");
    }

    /** <b>核心</b>：模型编造的链接，即便域名在白名单里，没真搜到就不算数。 */
    @Test
    void rejectsHallucinatedUrlNotInActualHits() {
        List<SearchHit> actual = List.of(hit("https://random-blog.com/post"));
        String v = cfg().verifyAuthorityUrl("https://en.wikipedia.org/wiki/Kubernetes", actual);
        assertEquals("", v, "白名单域名 + 没真搜到 = 不认，否则模型能凭空拿满分天花板");
    }

    /** 完全没搜到东西时，任何自报都不算数。 */
    @Test
    void rejectsAnyClaimWhenNoHits() {
        assertEquals("", cfg().verifyAuthorityUrl("https://wikipedia.org/x", List.of()));
        assertEquals("", cfg().verifyAuthorityUrl("https://wikipedia.org/x", null));
    }

    /** 模型给的链接确实在检索结果里且域名权威 → 认，且返回实际抓到的 URL。 */
    @Test
    void acceptsClaimBackedByActualHit() {
        List<SearchHit> actual = List.of(
                hit("https://random-blog.com/post"),
                hit("https://kubernetes.io/zh/docs/concepts/"));
        String v = cfg().verifyAuthorityUrl("https://kubernetes.io/zh/", actual);
        assertEquals("https://kubernetes.io/zh/docs/concepts/", v,
                "返回实际检索到的 URL，而不是模型写的那个（模型可能把路径记串）");
    }

    /** 模型没给链接，但检索结果里本就含权威来源 → 仍算命中（证据确实喂给它了）。 */
    @Test
    void acceptsAuthorityPresentInHitsWithoutClaim() {
        List<SearchHit> actual = List.of(hit("https://blog.com/x"), hit("https://nih.gov/study/1"));
        assertEquals("https://nih.gov/study/1", cfg().verifyAuthorityUrl(null, actual));
        assertEquals("https://nih.gov/study/1", cfg().verifyAuthorityUrl("", actual));
    }

    /** 搜到了但全是非权威来源 → 空串（⑤ 按「搜了没命中」计 0 分，不是特征缺失）。 */
    @Test
    void returnsEmptyWhenHitsAreAllNonAuthoritative() {
        List<SearchHit> actual = List.of(hit("https://blog.com/x"), hit("https://forum.net/y"));
        assertEquals("", cfg().verifyAuthorityUrl("https://blog.com/x", actual));
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
        assertEquals("", SearchConfig.none()
                .verifyAuthorityUrl("https://wikipedia.org/x", List.of(hit("https://wikipedia.org/x"))));
    }
}
