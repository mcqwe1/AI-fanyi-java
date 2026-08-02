package com.aifanyi.agent.search;

import com.aifanyi.agent.AgentHttp;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 博查搜索：{@code POST {base}/web-search}，鉴权 {@code Authorization: Bearer <key>}。
 *
 * <p>响应是 Bing 风格的三层嵌套（{@code data.webPages.value[]}），
 * 由 {@link SearchParsers} 的候选路径吸收。
 *
 * <p>它的价值在于国内直连且中文语料好——本产品主力场景是日/英→中，
 * 而「这个词中文圈通行的译法是什么」恰恰是中文搜索引擎才答得准的问题。
 */
@Slf4j
@RequiredArgsConstructor
@org.springframework.stereotype.Component
public class BochaSearchProvider implements SearchProvider {

    private final AgentHttp http;
    private final ObjectMapper json;

    @Override
    public String name() {
        return SearchEngines.Wire.BOCHA.name();
    }

    @Override
    public List<SearchHit> search(String query, SearchConfig cfg, long timeoutMs) {
        try {
            String url = AgentHttp.join(cfg.baseUrl(), "/web-search");
            Map<String, Object> body = new HashMap<>();
            body.put("query", query);
            body.put("count", Math.max(1, cfg.topK()));
            body.put("summary", true);        // 要正文摘要而非仅标题
            body.put("freshness", "noLimit"); // 术语译法无时效性，限制时间只会漏掉权威老页面

            AgentHttp.Result r = http.postJson(url,
                    Map.of("Authorization", "Bearer " + cfg.apiKey()),
                    json.writeValueAsString(body), timeoutMs);
            if (!r.ok()) {
                log.warn("博查搜索失败 status={} body={}", r.status(), brief(r.body()));
                return List.of();
            }
            return SearchParsers.parse(json.readTree(r.body()), cfg.topK(), "bocha", r.body());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.warn("博查搜索异常: {}", e.toString());
            return List.of();
        }
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
