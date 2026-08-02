package com.aifanyi.agent.search;

import com.aifanyi.agent.AgentHttp;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Serper（Google 搜索结果 API）：{@code POST {base}/search}，
 * 鉴权是<b>自定义头 {@code X-API-KEY}</b> 而非 Bearer。
 *
 * <p>返回的是 Google SERP 结构（{@code organic[]}），只有 snippet 没有正文，
 * 信息量低于 Tavily，但覆盖面最广——冷门专名往往只有 Google 搜得到。
 *
 * <p>注意它需要能连通 Google 基础设施；国内用户没梯子会连接超时，
 * 此时按契约返回空列表走不联网分支，不影响任务完成。
 */
@Slf4j
@RequiredArgsConstructor
@org.springframework.stereotype.Component
public class SerperSearchProvider implements SearchProvider {

    private final AgentHttp http;
    private final ObjectMapper json;

    @Override
    public String name() {
        return SearchEngines.Wire.SERPER.name();
    }

    @Override
    public List<SearchHit> search(String query, SearchConfig cfg, long timeoutMs) {
        try {
            String url = AgentHttp.join(cfg.baseUrl(), "/search");
            Map<String, Object> body = new HashMap<>();
            body.put("q", query);
            body.put("num", Math.max(1, cfg.topK()));
            // 不锁 gl/hl：查询词本身可能是任意语种，锁地区反而会削掉最相关的结果

            AgentHttp.Result r = http.postJson(url,
                    Map.of("X-API-KEY", cfg.apiKey()),
                    json.writeValueAsString(body), timeoutMs);
            if (!r.ok()) {
                log.warn("Serper 搜索失败 status={} body={}", r.status(), brief(r.body()));
                return List.of();
            }
            return SearchParsers.parse(json.readTree(r.body()), cfg.topK(), "serper", r.body());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.warn("Serper 搜索异常: {}", e.toString());
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
