package com.aifanyi.agent.search;

import com.aifanyi.agent.AgentHttp;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tavily 搜索：{@code POST {base}/search}，鉴权用 {@code Authorization: Bearer <key>}。
 *
 * <p>选它作首选的原因是它直接返回网页正文摘要（{@code content} 字段），
 * 不必二次抓取——子 Agent 判定「哪个译法是权威用法」需要的正是正文而非标题。
 *
 * <p>兼容性处理：老版本 Tavily 的鉴权是把 {@code api_key} 放在 body 里，
 * 新版本改成了 Bearer 头。两者<b>同时发送</b>——多余的字段各版本都会忽略，
 * 而少发一个就会在某个版本上 401。这比赌用户用的是哪版更稳。
 */
@Slf4j
@RequiredArgsConstructor
@org.springframework.stereotype.Component
public class TavilySearchProvider implements SearchProvider {

    private final AgentHttp http;
    private final ObjectMapper json;

    @Override
    public String name() {
        return SearchEngines.Wire.TAVILY.name();
    }

    @Override
    public List<SearchHit> search(String query, SearchConfig cfg, long timeoutMs) {
        try {
            String url = AgentHttp.join(cfg.baseUrl(), "/search");
            Map<String, Object> body = new HashMap<>();
            body.put("query", query);
            body.put("api_key", cfg.apiKey());          // 老版鉴权位置
            body.put("max_results", Math.max(1, cfg.topK()));
            body.put("search_depth", "basic");          // advanced 更贵更慢，术语核实用不上
            body.put("include_answer", false);          // 我们要证据不要它的结论
            body.put("include_raw_content", false);     // 正文全量会挤爆子 Agent 上下文

            AgentHttp.Result r = http.postJson(url,
                    Map.of("Authorization", "Bearer " + cfg.apiKey()),
                    json.writeValueAsString(body), timeoutMs);
            if (!r.ok()) {
                log.warn("Tavily 搜索失败 status={} body={}", r.status(), brief(r.body()));
                return List.of();
            }
            return SearchParsers.parse(json.readTree(r.body()), cfg.topK(), "tavily", r.body());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            // 契约：搜索永不抛异常，任何失败都是「无答案」的正常降级
            log.warn("Tavily 搜索异常: {}", e.toString());
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
