package com.aifanyi.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * LLM 响应 JSON 的容错解析（harness 工具层）。
 * <p>本产品要接任意 OpenAI 兼容端点（DeepSeek / Gemini 代理 / GLM / Kimi / 各类中转），
 * 各家吐 JSON 的毛病不一样。三级兜底，逐级放宽：
 * <ol>
 *   <li>直接 parse——规矩的端点走这条</li>
 *   <li>剥 markdown 围栏 + 大括号切片——治「解释一通再给 JSON」和 ```json 包裹
 *       （这招在 OpenAiTranslator 里已被生产验证）</li>
 *   <li>content 为空时读 reasoning_content——DeepSeek-R1 系在 max_tokens 截断时
 *       会把全部内容留在思维链字段里</li>
 * </ol>
 * <p>全程不抛异常：解析不出来返回 null，由调用方走降级路径。
 */
@Slf4j
public final class JsonEnvelope {

    private JsonEnvelope() {
    }

    /**
     * 从 OpenAI 兼容响应体里取出模型返回的 JSON 对象。
     *
     * @return 解析成功的 JSON 对象；任何失败返回 null
     */
    public static JsonNode extract(ObjectMapper mapper, String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (Exception e) {
            // 整个响应体都不是 JSON（网关吐了 HTML 错误页等）→ 直接当内容试一把
            return parseLoose(mapper, responseBody);
        }
        JsonNode msg = root.path("choices").path(0).path("message");
        String content = msg.path("content").asText("");
        if (content.isBlank()) {
            // 推理模型截断时内容会留在思维链字段
            content = msg.path("reasoning_content").asText("");
        }
        if (content.isBlank()) {
            return null;
        }
        return parseLoose(mapper, content);
    }

    /** 从模型返回的正文里抠出 JSON 对象：先直解，失败则剥围栏 + 大括号切片。 */
    public static JsonNode parseLoose(ObjectMapper mapper, String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String s = content.trim();
        // ① 直接解析
        try {
            JsonNode n = mapper.readTree(s);
            if (n != null && n.isObject()) {
                return n;
            }
        } catch (Exception ignored) {
            // 落到下一级
        }
        // ② 剥 markdown 围栏
        s = stripFences(s);
        try {
            JsonNode n = mapper.readTree(s);
            if (n != null && n.isObject()) {
                return n;
            }
        } catch (Exception ignored) {
            // 落到下一级
        }
        // ③ 大括号切片：治「这是结果：{...}，希望对你有帮助」
        // 先排除整体是数组的情况——否则会把数组里第一个元素当成信封捞出来，
        // 静默丢掉其余元素且结构完全不对（协议约定顶层必须是对象）
        String trimmed = s.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            log.debug("顶层是数组而非对象，按解析失败处理");
            return null;
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonNode n = mapper.readTree(s.substring(start, end + 1));
                if (n != null && n.isObject()) {
                    return n;
                }
            } catch (Exception ignored) {
                // 确实解不出来
            }
        }
        log.debug("JSON 解析失败，正文前 200 字: {}", s.length() > 200 ? s.substring(0, 200) : s);
        return null;
    }

    /** 去掉 ```json ... ``` 或 ``` ... ``` 围栏。 */
    private static String stripFences(String s) {
        String t = s.trim();
        if (!t.startsWith("```")) {
            return t;
        }
        int firstNl = t.indexOf('\n');
        if (firstNl < 0) {
            return t;
        }
        String body = t.substring(firstNl + 1);
        int close = body.lastIndexOf("```");
        return (close >= 0 ? body.substring(0, close) : body).trim();
    }

    /** 从响应体里取 usage 三元组（缺失为 0）；用于 Trace 落库与成本观测。 */
    public static long[] usage(ObjectMapper mapper, String responseBody) {
        try {
            JsonNode u = mapper.readTree(responseBody).path("usage");
            return new long[]{
                    u.path("prompt_tokens").asLong(0),
                    u.path("completion_tokens").asLong(0),
                    u.path("total_tokens").asLong(0)};
        } catch (Exception e) {
            return new long[]{0, 0, 0};
        }
    }

    /** 端点是否在抱怨 response_format 不支持（据此自动降级重试）。 */
    public static boolean looksLikeJsonModeUnsupported(String body) {
        if (body == null) {
            return false;
        }
        String s = body.toLowerCase(java.util.Locale.ROOT);
        return s.contains("response_format") || s.contains("json_object")
                || s.contains("json mode") || s.contains("not supported")
                || s.contains("unsupported") || s.contains("unrecognized");
    }
}
