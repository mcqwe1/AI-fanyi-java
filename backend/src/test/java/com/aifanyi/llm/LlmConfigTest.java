package com.aifanyi.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 协议与超时不能在「只想换个批大小」的时候被顺手丢掉。
 *
 * <p>这组用例守的是一个真实缺陷：文本/文档/划词三个模式拿到正确配置后用 6 参构造重建 LlmConfig，
 * 把用户选的 claude / deepl / 谷歌 / 微软协议静默改回 openai，于是设置页「测试连接」通过、
 * 视频翻译正常，唯独这三个模式全部打不通（实测 mock 端点：测试连接打 /v1/messages，
 * 文本翻译打 /v1/chat/completions）。
 */
class LlmConfigTest {

    private static LlmConfig claude() {
        return new LlmConfig("https://api.anthropic.com/v1", "sk-ant-x", "claude-sonnet-4-5",
                LlmConfig.PROTO_CLAUDE, 120, true, 40, 8);
    }

    @Test
    void withBatchSize只换批大小其余原样保留() {
        LlmConfig c = claude().withBatchSize(3);

        assertEquals(3, c.batchSize());
        // 下面每一条都是那个缺陷丢掉过的字段
        assertEquals(LlmConfig.PROTO_CLAUDE, c.protocol());
        assertTrue(c.isClaude());
        assertEquals(120, c.timeoutSec());
        assertEquals("https://api.anthropic.com/v1", c.baseUrl());
        assertEquals("sk-ant-x", c.apiKey());
        assertEquals("claude-sonnet-4-5", c.model());
        assertTrue(c.disableThinking());
        assertEquals(8, c.concurrency());
    }

    @Test
    void 机翻协议也要活过换批大小() {
        LlmConfig deepl = new LlmConfig("https://api-free.deepl.com", "k", "",
                LlmConfig.PROTO_DEEPL, 60, false, 40, 8);
        assertTrue(deepl.withBatchSize(5).isMt());
        assertEquals(LlmConfig.PROTO_DEEPL, deepl.withBatchSize(5).protocol());
    }

    @Test
    @SuppressWarnings("deprecation")
    void 六参构造只适用于本来就没有协议信息的场景() {
        // 保留它是为了 user_setting 里那几组裸 base/key/model（设置页只支持 OpenAI 格式端点）；
        // 这条用例把它的行为钉死，防止有人误以为它能"保留"什么
        LlmConfig legacy = new LlmConfig("http://x", "k", "m", true, 40, 8);
        assertEquals(LlmConfig.PROTO_OPENAI, legacy.protocol());
        assertEquals(60, legacy.timeoutSec());
    }

    @Test
    void 超时越界回退六十秒() {
        assertEquals(120, claude().withBatchSize(1).effectiveTimeoutSec());
        LlmConfig bad = new LlmConfig("http://x", "k", "m", LlmConfig.PROTO_OPENAI, 3, true, 40, 8);
        assertEquals(60, bad.effectiveTimeoutSec());
        LlmConfig ok = new LlmConfig("http://x", "k", "m", LlmConfig.PROTO_OPENAI, 200, true, 40, 8);
        assertEquals(200, ok.effectiveTimeoutSec());
    }
}
