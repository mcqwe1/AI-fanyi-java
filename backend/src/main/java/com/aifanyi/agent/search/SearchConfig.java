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
     * <p>这是分诊 A 档（权威佐证）的唯一入口。模型在步骤 C 里可以随口写
     * {@code "strategy":"AUTHORITATIVE"} 或编一个看着像模像样的 wikipedia 链接；
     * 若采信，它就能凭空拿到最高档并自动入库启用。
     *
     * <p><b>2026-08 补的第二道闸：命中的结果必须真的谈到这个词。</b>
     * 旧版只要检索结果里出现任意一个白名单域名就算命中——哪怕那个维基页面
     * 跟要查的词毫无关系（搜索引擎返回的旁支结果）。那等于「搜到过维基百科」
     * 就能兑换最高档，白名单形同虚设。
     *
     * @param claimedUrl 模型自报的来源链接（可为空）
     * @param hits       本次<b>实际检索到</b>的结果
     * @param source     原文术语（用于核验该结果确实在谈这个词）
     * @param target     译法（同上；中文译法出现在英文权威页的概率低，故两者命中其一即可）
     * @return 核验通过的权威 URL；搜过但无权威命中返回空串（区别于「没搜过」的 null）
     */
    public String verifyAuthorityUrl(String claimedUrl, List<SearchHit> hits,
                                     String source, String target) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        // ① 模型给了链接：必须确实出自我们检索到的结果，域名在白名单内，且该结果谈到了这个词。
        //    返回实际抓到的 URL 而非模型写的那个——模型可能把路径记串。
        String claimed = SearchHit.extractDomain(claimedUrl);
        if (!claimed.isBlank()) {
            for (SearchHit h : hits) {
                if (claimed.equals(h.domain()) && isAuthority(h.domain()) && mentions(h, source, target)) {
                    return h.url();
                }
            }
        }
        // ② 模型没给链接或对不上：检索结果里本就含（谈到该词的）权威来源时仍算命中——
        //    这些结果是随 prompt 一起喂给模型的，它的判断确实建立在其上。
        for (SearchHit h : hits) {
            if (isAuthority(h.domain()) && mentions(h, source, target)) {
                return h.url();
            }
        }
        return "";
    }

    /**
     * 这条检索结果是否真的谈到了这个术语。
     * <p>看标题、摘要与 <b>URL</b>——正文我们没抓，也不该为了核验再发一轮 HTTP。
     * URL 要算进来：百科类条目的路径里通常就是词本身（/wiki/Kubernetes），
     * 那是比摘要更硬的信号，漏掉它会把最典型的真命中误杀。
     * <p>判定放宽到「原文或译文命中其一」：要求两者同时出现会把英文权威页全部误杀
     * （en.wikipedia 的页面不会写中文译法），那就从一个漏洞换成另一个极端。
     */
    static boolean mentions(SearchHit h, String source, String target) {
        if (h == null) {
            return false;
        }
        String hay = ((h.title() == null ? "" : h.title()) + " "
                + (h.snippet() == null ? "" : h.snippet()) + " "
                + (h.url() == null ? "" : h.url())).toLowerCase(java.util.Locale.ROOT);
        return contains(hay, source) || contains(hay, target);
    }

    /** null/空串绝不参与匹配——否则空译法会让每条结果都「命中」。 */
    private static boolean contains(String hay, String needle) {
        return needle != null && !needle.isBlank()
                && hay.contains(needle.trim().toLowerCase(java.util.Locale.ROOT));
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
