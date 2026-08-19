package com.aifanyi.llm;

import java.util.List;

/**
 * 大语言模型/翻译服务商注册表（「设置 → API 配置 → 大语言模型」的服务商下拉）。
 * <p>与 SearchEngines/TtsEngines 同一模式：后端是唯一事实源，前端只渲染。
 * <p>Base URL 规则：openai/claude 协议保存时若无版本段会自动补 /v1（见 SettingsService.normalizeBaseUrl）；
 * 传统机翻（deepl/google-mt/ms-mt）用各家固定端点，不做补全。
 */
public final class LlmProviders {

    /**
     * @param id            服务商 id（存进 model_service.provider）
     * @param name          展示名
     * @param protocol      通信协议（LlmConfig.PROTO_*）
     * @param defaultBaseUrl 预填 Base URL（自定义类为空，用户自己填）
     * @param defaultModel  模型占位建议（可被「拉取模型」覆盖）
     * @param keyHint       API Key 获取提示
     * @param docsUrl       控制台/文档地址
     * @param note          特殊说明（展示在表单下方）
     * @param needModel     是否需要「模型」字段（机翻不需要；微软用它存区域）
     * @param canListModels 是否支持 GET /models 拉取列表
     * @param modelLabel    模型字段的标签（微软翻译=「区域」）
     * @param custom        是否自定义类（Base URL 必填可编辑）
     */
    public record Provider(String id, String name, String protocol,
                           String defaultBaseUrl, String defaultModel,
                           String keyHint, String docsUrl, String note,
                           boolean needModel, boolean canListModels, String modelLabel,
                           boolean custom) {
    }

    private static final List<Provider> ALL = List.of(
            new Provider("deepseek", "DeepSeek", LlmConfig.PROTO_OPENAI,
                    "https://api.deepseek.com/v1", "deepseek-chat",
                    "platform.deepseek.com 注册后在「API Keys」创建（sk- 开头）",
                    "https://platform.deepseek.com", "性价比高，翻译推荐首选。",
                    true, true, "模型", false),
            new Provider("qwen", "Qwen（通义千问）", LlmConfig.PROTO_OPENAI,
                    "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus",
                    "阿里云百炼控制台创建 API-KEY（sk- 开头）",
                    "https://bailian.console.aliyun.com", null,
                    true, true, "模型", false),
            new Provider("gemini", "Gemini（谷歌）", LlmConfig.PROTO_OPENAI,
                    "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash",
                    "aistudio.google.com 创建 API Key（国内需魔法）",
                    "https://aistudio.google.com/apikey", "走谷歌官方 OpenAI 兼容端点。",
                    true, true, "模型", false),
            new Provider("gpt", "GPT（OpenAI）", LlmConfig.PROTO_OPENAI,
                    "https://api.openai.com/v1", "gpt-4o-mini",
                    "platform.openai.com 创建 API Key（国内需魔法）",
                    "https://platform.openai.com/api-keys", null,
                    true, true, "模型", false),
            new Provider("glm", "GLM（智谱）", LlmConfig.PROTO_OPENAI,
                    "https://open.bigmodel.cn/api/paas/v4", "glm-4-air",
                    "open.bigmodel.cn 控制台创建 API Key",
                    "https://open.bigmodel.cn", null,
                    true, true, "模型", false),
            new Provider("claude", "Claude（Anthropic）", LlmConfig.PROTO_CLAUDE,
                    "https://api.anthropic.com/v1", "claude-sonnet-4-5",
                    "console.anthropic.com 创建 API Key（国内需魔法）",
                    "https://console.anthropic.com", "使用 Anthropic 原生 messages 协议。",
                    true, true, "模型", false),
            new Provider("grok", "Grok（xAI）", LlmConfig.PROTO_OPENAI,
                    "https://api.x.ai/v1", "grok-3-mini",
                    "console.x.ai 创建 API Key（国内需魔法）",
                    "https://console.x.ai", null,
                    true, true, "模型", false),
            new Provider("deepl", "DeepL", LlmConfig.PROTO_DEEPL,
                    "https://api-free.deepl.com/v2", null,
                    "deepl.com/pro-api 注册，免费版 Key 以 :fx 结尾",
                    "https://www.deepl.com/pro-api",
                    "传统机器翻译：速度快、不支持翻译风格与术语提示。Pro 付费版把 Base URL 换成 https://api.deepl.com/v2。",
                    false, false, "模型", false),
            new Provider("google-mt", "谷歌翻译", LlmConfig.PROTO_GOOGLE_MT,
                    "https://translation.googleapis.com/language/translate/v2", null,
                    "Google Cloud 控制台启用 Cloud Translation API 后创建 API 密钥（国内需魔法）",
                    "https://console.cloud.google.com/apis/library/translate.googleapis.com",
                    "传统机器翻译：速度快、不支持翻译风格与术语提示。",
                    false, false, "模型", false),
            new Provider("ms-mt", "微软翻译", LlmConfig.PROTO_MS_MT,
                    "https://api.cognitive.microsofttranslator.com", null,
                    "Azure 门户创建「翻译工具」资源，取密钥与区域",
                    "https://portal.azure.com",
                    "传统机器翻译：速度快、不支持翻译风格与术语提示。区域填资源所在区（如 eastasia），全球资源留空。",
                    false, false, "区域", false),
            new Provider("custom-openai", "自定义（OpenAI 通用格式）", LlmConfig.PROTO_OPENAI,
                    "", null,
                    "填你的中转站 / 自建端点的 Key",
                    null, "任何 OpenAI 兼容端点（中转站、one-api、ollama 等）。",
                    true, true, "模型", true),
            new Provider("custom-claude", "自定义（Claude 通用格式）", LlmConfig.PROTO_CLAUDE,
                    "", null,
                    "填你的 Claude 兼容端点的 Key",
                    null, "任何 Anthropic messages 协议兼容端点。",
                    true, true, "模型", true)
    );

    private LlmProviders() {
    }

    public static List<Provider> all() {
        return ALL;
    }

    public static Provider byId(String id) {
        if (id == null) {
            return null;
        }
        for (Provider p : ALL) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** 展示名（列表用）；未知 id 原样返回。 */
    public static String displayName(String id) {
        Provider p = byId(id);
        return p == null ? id : p.name();
    }
}
