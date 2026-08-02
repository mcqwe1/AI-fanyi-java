package com.aifanyi.agent.search;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索响应的容错解析（照 {@link com.aifanyi.agent.llm.JsonEnvelope} 的三级兜底哲学）。
 *
 * <p><b>为什么不按单一字段名硬解</b>：搜索 API 的响应结构比 LLM 的还不稳定——
 * 同一家会因版本/套餐返回 {@code content} 或 {@code snippet} 或 {@code description}，
 * 国内几家又普遍套一层 {@code data}。写死一条路径的后果是<b>静默返回空列表</b>：
 * 上游把「无结果」当正常降级处理（见 SearchProvider 契约），于是配好了 Key 也永远走
 * 不联网分支，且日志里看不出任何异常。
 *
 * <p>所以这里按「候选路径依次试探」解析，并在<b>响应体非空却一条都没解出来</b>时
 * 打一条 warn 带响应片段——让配置问题和格式变更能被看见，而不是消失在降级路径里。
 */
@Slf4j
public final class SearchParsers {

    /** 结果数组的候选路径：各家（及其版本差异）常见的几种嵌套。 */
    private static final String[][] LIST_PATHS = {
            {"results"},                    // Tavily
            {"organic"},                    // Serper
            {"data", "webPages", "value"},  // 博查（Bing 风格三层嵌套）
            {"webPages", "value"},          // 同上，未套 data 的变体
            {"data", "results"},
            {"data", "webPages"},
            {"items"},                      // Google CSE 风格
            {"data"},                       // 兜底：data 直接是数组
    };

    /** 摘要字段的候选名，按信息量从多到少——Tavily 的 content 是正文摘要，优于一句话 snippet。 */
    private static final String[] SNIPPET_KEYS = {
            "content", "snippet", "description", "summary", "text", "body"};

    /** 链接字段的候选名。 */
    private static final String[] URL_KEYS = {"url", "link", "displayUrl", "href", "siteName"};

    /** 标题字段的候选名。 */
    private static final String[] TITLE_KEYS = {"title", "name", "heading"};

    private SearchParsers() {
    }

    /**
     * 从任意一家的响应里抽出结果列表。
     *
     * @param root     解析后的响应根节点（null 安全）
     * @param topK     最多取几条
     * @param provider provider 名（仅用于日志定位）
     * @param rawBody  原始响应体（仅用于解析失败时打片段）
     * @return 结果列表；解析不出返回空列表，绝不抛异常
     */
    public static List<SearchHit> parse(JsonNode root, int topK, String provider, String rawBody) {
        List<SearchHit> out = new ArrayList<>();
        if (root == null || topK <= 0) {
            return out;
        }
        JsonNode arr = findArray(root);
        if (arr == null) {
            warnUnparsed(provider, rawBody);
            return out;
        }
        for (JsonNode n : arr) {
            if (out.size() >= topK) {
                break;
            }
            if (!n.isObject()) {
                continue;
            }
            String url = firstText(n, URL_KEYS);
            String snippet = firstText(n, SNIPPET_KEYS);
            String title = firstText(n, TITLE_KEYS);
            // 摘要和链接全无的条目没有任何判定价值，跳过
            if (snippet.isBlank() && url.isBlank()) {
                continue;
            }
            out.add(SearchHit.of(title, url, clamp(snippet)));
        }
        if (out.isEmpty()) {
            warnUnparsed(provider, rawBody);
        }
        return out;
    }

    /** 按候选路径依次试探，取第一个非空数组。 */
    private static JsonNode findArray(JsonNode root) {
        for (String[] path : LIST_PATHS) {
            JsonNode n = root;
            for (String seg : path) {
                n = n.path(seg);
            }
            if (n.isArray() && !n.isEmpty()) {
                return n;
            }
        }
        return null;
    }

    /** 取第一个存在且非空的字段值。 */
    private static String firstText(JsonNode n, String[] keys) {
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && v.isTextual()) {
                String s = v.asText().trim();
                if (!s.isBlank()) {
                    return s;
                }
            }
        }
        return "";
    }

    /**
     * 截断摘要：搜索正文会直接进子 Agent 的 prompt，不截断会挤爆上下文
     * （Tavily 的 raw content 单条可达数千字，5 条就顶掉整个摘要预算）。
     */
    private static String clamp(String s) {
        int max = 500;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 有响应但解不出结果——这是配置错误/格式变更的信号，不能沉默。 */
    private static void warnUnparsed(String provider, String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return;
        }
        String s = rawBody.length() > 300 ? rawBody.substring(0, 300) + "…" : rawBody;
        log.warn("搜索 [{}] 返回了内容但未解析出结果，响应片段: {}", provider, s);
    }
}
