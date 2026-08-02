package com.aifanyi.agent.search;

import java.util.List;

/**
 * 一次搜索调用的有效配置（照 LlmConfig/TtsConfig 范式，由 SettingsService 解析）。
 *
 * @param engineId         引擎 id；none/未配置表示不联网
 * @param wire             协议形态（各家差异在注册表吸收）
 * @param baseUrl          端点
 * @param apiKey           密钥
 * @param topK             取前几条
 * @param authorityDomains 权威域名列表（⑤ 置信度的权威命中特征据此判定）
 */
public record SearchConfig(String engineId, SearchEngines.Wire wire, String baseUrl,
                           String apiKey, int topK, List<String> authorityDomains) {

    /** 不联网（未配置或用户显式选择不搜）。 */
    public static SearchConfig none() {
        return new SearchConfig("none", SearchEngines.Wire.NONE, "", "", 0, List.of());
    }

    public boolean enabled() {
        return wire != SearchEngines.Wire.NONE;
    }

    /**
     * 核验「权威来源」这件事——<b>只认代码检索到的结果，不认模型自报</b>。
     *
     * <p>这是 ⑤ 置信度 0.95 天花板的唯一入口。模型在步骤 C 里可以随口写
     * {@code "strategy":"AUTHORITATIVE"} 或编一个看着像模像样的 wikipedia 链接；
     * 若采信，它就能凭空拿到最高档天花板并自动 VERIFIED 进用户术语库——
     * 这正是 ConfidenceScorer 的天花板机制要拦的「自报当证据」，
     * 只不过换成从另一个字段溜进来。
     *
     * @param claimedUrl 模型自报的来源链接（可为空）
     * @param hits       本次<b>实际检索到</b>的结果
     * @return 核验通过的权威 URL；搜过但无权威命中返回空串（该特征按 0 分计入，
     *         区别于「没搜过」的 null——后者是特征缺失，参与重归一）
     */
    public String verifyAuthorityUrl(String claimedUrl, List<SearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        // ① 模型给了链接：必须确实出自我们检索到的结果，且域名在权威白名单内。
        //    返回实际抓到的 URL 而非模型写的那个——模型可能把路径记串。
        String claimed = SearchHit.extractDomain(claimedUrl);
        if (!claimed.isBlank()) {
            for (SearchHit h : hits) {
                if (claimed.equals(h.domain()) && isAuthority(h.domain())) {
                    return h.url();
                }
            }
        }
        // ② 模型没给链接或对不上：检索结果里本就含权威来源时仍算命中——
        //    这些结果是随 prompt 一起喂给模型的，它的判断确实建立在其上。
        for (SearchHit h : hits) {
            if (isAuthority(h.domain())) {
                return h.url();
            }
        }
        return "";
    }

    /** 该域名是否属于权威来源。 */
    public boolean isAuthority(String domain) {
        if (domain == null || domain.isBlank() || authorityDomains.isEmpty()) {
            return false;
        }
        String d = domain.toLowerCase(java.util.Locale.ROOT);
        for (String a : authorityDomains) {
            if (d.equals(a) || d.endsWith("." + a)) {
                return true;
            }
        }
        return false;
    }
}
