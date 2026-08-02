package com.aifanyi.agent.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * -search 后缀剥除单测。
 * 这是「主Agent无联网工具(物理防慢)」的代码保证：带该后缀的模型会让代理侧自行联网，
 * 次数不可控、耗时超线性（实测 7000 字 320 秒），塞进 30 秒预算必然超时。
 */
class StripSearchSuffixTest {

    @Test
    void stripsSuffix() {
        assertEquals("gemini-3-flash-preview",
                AgentJsonClient.stripSearchSuffix("gemini-3-flash-preview-search"));
    }

    @Test
    void caseInsensitive() {
        assertEquals("gemini-pro", AgentJsonClient.stripSearchSuffix("gemini-pro-SEARCH"));
    }

    @Test
    void leavesNormalModelsAlone() {
        assertEquals("deepseek-chat", AgentJsonClient.stripSearchSuffix("deepseek-chat"));
        assertEquals("gpt-4o", AgentJsonClient.stripSearchSuffix("gpt-4o"));
        // 名字里含 search 但不是后缀的不能误伤
        assertEquals("search-model-v2", AgentJsonClient.stripSearchSuffix("search-model-v2"));
    }

    @Test
    void trimsWhitespace() {
        assertEquals("glm-4", AgentJsonClient.stripSearchSuffix("  glm-4  "));
    }

    @Test
    void handlesNull() {
        assertNull(AgentJsonClient.stripSearchSuffix(null));
    }
}
