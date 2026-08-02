package com.aifanyi.agent.llm;

import com.aifanyi.agent.AgentBudget;
import com.aifanyi.agent.AgentHttp;
import com.aifanyi.llm.LlmConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 的唯一 LLM 出口（harness 工具层）。
 * <p><b>协议选择：结构化 JSON，不用 tools/tool_calls。</b>
 * 本产品要接任意 OpenAI 兼容端点，而 tools 协议在各家的支持度与字段差异极大
 * （项目已有实证：vertex2openai 连 {@code thinking:{type:disabled}} 都不认，
 * 要靠 {@code -nothinking} 模型名后缀）。且架构明令「内部固定DAG（无自由循环）」——
 * tools 的价值是让模型动态选工具，而这正是我们禁止的能力。
 * 于是：模型只管返回 {@code {"status":"NEED_SEARCH","queries":[...]}}，
 * <b>由代码</b>执行搜索、<b>由代码</b>把结果拼进下一轮 prompt、<b>由代码</b>数次数。
 * <p>本类绝不抛业务异常：任何失败返回 null，调用方降级。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentJsonClient {

    private final ObjectMapper mapper;
    private final AgentHttp http;

    /** 观察到拒绝 response_format 的端点（键=baseUrl|model）。进程内记忆，重启即忘。 */
    private final Set<String> noJsonMode = ConcurrentHashMap.newKeySet();

    /** 一次调用的提示词。 */
    public record Prompt(String system, String user, double temperature) {
        public static Prompt of(String system, String user) {
            return new Prompt(system, user, 0.2);
        }
    }

    /** 调用结果：解析出的 JSON + 观测数据（供 Trace 落库）。 */
    public record Call(JsonNode json, long elapsedMs, long promptTokens, long completionTokens,
                       long totalTokens, String stopReason) {
        public boolean ok() {
            return json != null;
        }
    }

    /**
     * 发一次请求并解析出 JSON 对象。
     * <p>预算与超时全在这里兜底：步骤额度、请求额度、逐请求超时都取自 budget。
     *
     * @return 永不为 null 的 Call；解析失败时其 json 为 null、stopReason 说明原因
     */
    public Call callJson(Prompt prompt, LlmConfig cfg, AgentBudget budget, String tag) {
        long t0 = System.currentTimeMillis();
        if (!budget.tryLlmStep()) {
            return new Call(null, 0, 0, 0, 0, "BUDGET");
        }
        String url = AgentHttp.join(cfg.baseUrl(), "/chat/completions");
        String key = cfg.baseUrl() + "|" + cfg.model();
        boolean useJsonMode = !noJsonMode.contains(key);

        // 最多两次：一次正常发送 + 一次条件重试（网络错 / 完全解析失败 / json_object 被拒）
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (!budget.tryRequest()) {
                return new Call(null, System.currentTimeMillis() - t0, 0, 0, 0, "BUDGET");
            }
            long left = budget.remainingMs();
            if (left < AgentHttp.MIN_TIMEOUT_MS) {
                return new Call(null, System.currentTimeMillis() - t0, 0, 0, 0, "TIMEOUT");
            }

            AgentHttp.Result res;
            try {
                res = http.postJson(url, Map.of("Authorization", "Bearer " + cfg.apiKey()),
                        buildBody(prompt, cfg, useJsonMode), left);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return new Call(null, System.currentTimeMillis() - t0, 0, 0, 0, "CANCELLED");
            }

            if (res.status() == 0) {
                log.debug("[{}] 网络失败(第{}次): {}", tag, attempt, res.body());
                continue;                                   // 网络错 → 重试
            }
            if (!res.ok()) {
                // 端点拒绝 json_object → 记住并去掉该字段重试一次
                if (useJsonMode && res.status() >= 400 && res.status() < 500
                        && JsonEnvelope.looksLikeJsonModeUnsupported(res.body())) {
                    log.info("[{}] 端点不支持 response_format，已记忆并降级重试: {}", tag, cfg.model());
                    noJsonMode.add(key);
                    useJsonMode = false;
                    continue;
                }
                log.debug("[{}] HTTP {}: {}", tag, res.status(), truncate(res.body(), 200));
                return new Call(null, System.currentTimeMillis() - t0, 0, 0, 0,
                        "HTTP_" + res.status());
            }

            JsonNode json = JsonEnvelope.extract(mapper, res.body());
            long[] u = JsonEnvelope.usage(mapper, res.body());
            if (json != null) {
                long ms = System.currentTimeMillis() - t0;
                log.debug("[{}] OK {}ms tokens={}", tag, ms, u[2]);
                return new Call(json, ms, u[0], u[1], u[2], "OK");
            }
            log.debug("[{}] 解析失败(第{}次)", tag, attempt);
            // 完全解析不出来 → 重试一次；仍失败则降级
        }
        return new Call(null, System.currentTimeMillis() - t0, 0, 0, 0, "PARSE_FAIL");
    }

    private String buildBody(Prompt p, LlmConfig cfg, boolean jsonMode) {
        ObjectNode req = mapper.createObjectNode();
        req.put("model", stripSearchSuffix(cfg.model()));
        req.put("temperature", p.temperature());
        if (jsonMode) {
            req.putObject("response_format").put("type", "json_object");
        }
        if (cfg.disableThinking()) {
            req.putObject("thinking").put("type", "disabled");
        }
        ArrayNode msgs = req.putArray("messages");
        msgs.addObject().put("role", "system").put("content", p.system());
        msgs.addObject().put("role", "user").put("content", p.user());
        try {
            return mapper.writeValueAsString(req);
        } catch (Exception e) {
            throw new IllegalStateException("构造请求体失败", e);
        }
    }

    /**
     * 剥掉模型名的 {@code -search} 后缀。
     * <p>该后缀会让代理侧自行联网，查询次数完全不可控——GeminiClient 的注释记录了实测后果：
     * 3500 字约 21 秒，7000 字约 320 秒。塞进 30 秒预算的子 Agent 里必然超时。
     * <p>Agent 的联网一律由 SearchEngines 显式执行、由代码计次。这是把架构的
     * 「主Agent无联网工具(物理防慢)」变成代码保证，而不是配置期望。
     */
    public static String stripSearchSuffix(String model) {
        if (model == null) {
            return null;
        }
        String m = model.trim();
        String lower = m.toLowerCase(Locale.ROOT);
        if (lower.endsWith("-search")) {
            return m.substring(0, m.length() - "-search".length());
        }
        return m;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
