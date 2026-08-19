package com.aifanyi.agent.search;

import java.util.List;

/**
 * 搜索引擎注册表（单一事实源，照 {@link com.aifanyi.tts.TtsEngines} 范式）。
 * <p>各家的 wire format 差异全部在这里吸收，用户在设置页只看到「联网核实」开关 + 引擎卡片。
 * <p><b>为什么不指望「模型自带联网」</b>：Gemini 的 grounding、GLM 的 web_search、
 * Kimi 的 $web_search、OpenAI 的 web_search 各自写法都不一样，且都不是 OpenAI 兼容
 * chat/completions 的标准部分；而本产品主推的 DeepSeek 直连根本没有联网能力。
 * 靠模型自带会在大部分用户配置下静默失效。
 */
public final class SearchEngines {

    /** 协议形态：provider 实现按此分派。 */
    public enum Wire {
        /** Tavily：面向 LLM 的搜索 API，直接返回正文摘要 */
        TAVILY,
        /** 博查：国内直连，中文语料好 */
        BOCHA,
        /** Serper：Google 搜索结果 API */
        SERPER,
        /** 复用带 -search 后缀的联网模型（次数不可控，仅兜底） */
        /** 不联网 */
        NONE
    }

    /**
     * @param id               引擎标识（存 user_setting.search_provider）
     * @param name             展示名
     * @param desc             一句话介绍（设置页卡片）
     * @param tag              cloud=云端需Key / custom=自定义 / free=免配置
     * @param needKey          是否需要 API Key
     * @param defaultBaseUrl   预设端点
     * @param wire             协议形态
     * @param defaultTopK      默认取前几条
     * @param authorityDomains 该引擎语境下的权威域名（喂给 ⑤ 的权威命中特征）
     */
    public record Engine(String id, String name, String desc, String tag, boolean needKey,
                         String defaultBaseUrl, Wire wire, int defaultTopK,
                         List<String> authorityDomains) {
    }

    /** 国际通用权威源：百科、官方文档站、标准组织 */
    private static final List<String> AUTHORITY_GLOBAL = List.of(
            "wikipedia.org", "wikidata.org", "britannica.com",
            "who.int", "nih.gov", "ncbi.nlm.nih.gov",
            "iso.org", "ietf.org", "w3.org", "unicode.org");

    /** 国内权威源：百科、官方媒体、标准与行业机构 */
    private static final List<String> AUTHORITY_CN = List.of(
            "baike.baidu.com", "zh.wikipedia.org", "cnki.net",
            "gov.cn", "org.cn", "edu.cn",
            "termonline.cn", "cnctst.cn");

    public static final Engine TAVILY = new Engine(
            "tavily", "Tavily 搜索",
            "面向 AI 的搜索接口，直接返回网页正文摘要、无需二次抓取，判定译法准确率高。到 tavily.com 注册领取 API Key。",
            "cloud", true, "https://api.tavily.com", Wire.TAVILY, 5,
            concat(AUTHORITY_GLOBAL, AUTHORITY_CN));

    public static final Engine BOCHA = new Engine(
            "bocha", "博查搜索",
            "国内直连、中文语料好、按量计费，无需梯子。到 bochaai.com 注册领取 API Key。",
            "cloud", true, "https://api.bochaai.com/v1", Wire.BOCHA, 5,
            concat(AUTHORITY_CN, AUTHORITY_GLOBAL));

    public static final Engine SERPER = new Engine(
            "serper", "Serper（Google）",
            "Google 搜索结果 API，覆盖面最广，需要能连通 Google。到 serper.dev 注册领取 API Key。",
            "cloud", true, "https://google.serper.dev", Wire.SERPER, 5,
            AUTHORITY_GLOBAL);

    public static final Engine NONE = new Engine(
            "none", "不联网",
            "子 Agent 仅用模型自身知识与上下文证据定译。最快最省，但生僻专名的译法准确率下降。",
            "free", false, "", Wire.NONE, 0, List.of());

    private static final List<Engine> ALL = List.of(TAVILY, BOCHA, SERPER, NONE);

    private SearchEngines() {
    }

    public static List<Engine> all() {
        return ALL;
    }

    /** 按 id 取引擎；未知/空返回 null（调用方按「不联网」降级，不报错）。 */
    public static Engine byId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String key = id.trim().toLowerCase(java.util.Locale.ROOT);
        for (Engine e : ALL) {
            if (e.id().equals(key)) {
                return e;
            }
        }
        return null;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>(a);
        set.addAll(b);
        return List.copyOf(set);
    }
}
