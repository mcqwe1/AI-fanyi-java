package com.aifanyi.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonEnvelope 单测：异构端点的畸形响应必须都能兜住。
 * 用例取自各家真实会出现的返回形态——这是 Agent 能接任意 OpenAI 兼容端点的前提。
 */
class JsonEnvelopeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static String wrap(String content) {
        // content 需转义成 JSON 字符串
        String esc = content.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + esc + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":22,\"total_tokens\":33}}";
    }

    /** ① 规矩的端点：content 就是纯 JSON。 */
    @Test
    void parsesCleanJson() {
        JsonNode n = JsonEnvelope.extract(mapper, wrap("{\"status\":\"DONE\",\"terms\":[]}"));
        assertNotNull(n);
        assertEquals("DONE", n.path("status").asText());
    }

    /** ② markdown 围栏包裹（GLM/Kimi 常见）。 */
    @Test
    void stripsMarkdownFences() {
        JsonNode n = JsonEnvelope.extract(mapper,
                wrap("```json\n{\"status\":\"NEED_SEARCH\",\"queries\":[\"a\",\"b\"]}\n```"));
        assertNotNull(n);
        assertEquals("NEED_SEARCH", n.path("status").asText());
        assertEquals(2, n.path("queries").size());
    }

    /** ② 无语言标记的围栏。 */
    @Test
    void stripsBareFences() {
        JsonNode n = JsonEnvelope.extract(mapper, wrap("```\n{\"status\":\"DONE\"}\n```"));
        assertNotNull(n);
        assertEquals("DONE", n.path("status").asText());
    }

    /** ② 前后有解释文字（模型不听话时最常见）。 */
    @Test
    void slicesOutJsonFromChatter() {
        JsonNode n = JsonEnvelope.extract(mapper,
                wrap("好的，我分析了一下，结果如下：{\"status\":\"DONE\",\"terms\":[{\"source\":\"API\"}]}"
                        + " 希望对你有帮助！"));
        assertNotNull(n);
        assertEquals("API", n.path("terms").path(0).path("source").asText());
    }

    /** ③ content 为空但 reasoning_content 有内容（DeepSeek-R1 系截断时）。 */
    @Test
    void fallsBackToReasoningContent() {
        String body = "{\"choices\":[{\"message\":{\"content\":\"\","
                + "\"reasoning_content\":\"{\\\"status\\\":\\\"DONE\\\"}\"}}]}";
        JsonNode n = JsonEnvelope.extract(mapper, body);
        assertNotNull(n);
        assertEquals("DONE", n.path("status").asText());
    }

    /** 彻底解析不出来时返回 null，不抛异常。 */
    @Test
    void returnsNullOnGarbage() {
        assertNull(JsonEnvelope.extract(mapper, wrap("我不知道该怎么回答这个问题")));
        assertNull(JsonEnvelope.extract(mapper, "<html><body>502 Bad Gateway</body></html>"));
        assertNull(JsonEnvelope.extract(mapper, ""));
        assertNull(JsonEnvelope.extract(mapper, null));
    }

    /** 数组（而非对象）不算有效信封——我们的协议约定顶层必须是对象。 */
    @Test
    void rejectsTopLevelArray() {
        assertNull(JsonEnvelope.extract(mapper, wrap("[{\"source\":\"API\"}]")));
    }

    @Test
    void extractsUsage() {
        long[] u = JsonEnvelope.usage(mapper, wrap("{\"status\":\"DONE\"}"));
        assertArrayEquals(new long[]{11, 22, 33}, u);
    }

    @Test
    void usageMissingIsZeros() {
        assertArrayEquals(new long[]{0, 0, 0}, JsonEnvelope.usage(mapper, "{\"choices\":[]}"));
        assertArrayEquals(new long[]{0, 0, 0}, JsonEnvelope.usage(mapper, "not json"));
    }

    /** json_object 不被支持的各种报错措辞都要能识别（据此自动降级重试）。 */
    @Test
    void detectsJsonModeUnsupported() {
        assertTrue(JsonEnvelope.looksLikeJsonModeUnsupported(
                "{\"error\":{\"message\":\"response_format is not supported\"}}"));
        assertTrue(JsonEnvelope.looksLikeJsonModeUnsupported("Unrecognized field: json_object"));
        assertTrue(JsonEnvelope.looksLikeJsonModeUnsupported("JSON mode unsupported for this model"));
        assertFalse(JsonEnvelope.looksLikeJsonModeUnsupported("rate limit exceeded"));
        assertFalse(JsonEnvelope.looksLikeJsonModeUnsupported(null));
    }
}
