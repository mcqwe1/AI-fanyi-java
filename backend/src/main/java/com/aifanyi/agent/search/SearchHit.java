package com.aifanyi.agent.search;

/**
 * 一条搜索结果。
 *
 * @param title   标题
 * @param url     链接
 * @param snippet 正文摘要（喂给 LLM 判定权威译法）
 * @param domain  域名（用于权威来源判定，见 SearchEngines.authorityDomains）
 */
public record SearchHit(String title, String url, String snippet, String domain) {

    public static SearchHit of(String title, String url, String snippet) {
        return new SearchHit(title, url, snippet, extractDomain(url));
    }

    public static String extractDomain(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String host = java.net.URI.create(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }
}
