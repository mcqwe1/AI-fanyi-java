package com.aifanyi.controller.dto;

public class SettingsDtos {

    /** 单个密钥的展示态：是否已配置 + 打码值 */
    public record SecretView(boolean set, String masked) {
    }

    public record SettingsVO(
            SecretView groqApiKey,
            String llmBaseUrl,
            SecretView llmApiKey,
            String llmModel,
            SecretView dashscopeApiKey,
            SecretView zhipuApiKey,
            String geminiBaseUrl,
            SecretView geminiApiKey,
            String geminiModel,
            String ttsProvider,
            String ttsBaseUrl,
            SecretView ttsApiKey,
            String ttsModel,
            String stylePrompt,
            String termExtractPrompt,
            String defaultTermPrompt,
            // ---- Agent 模式（全能 AI 翻译）----
            String agentMainBaseUrl,
            SecretView agentMainApiKey,
            String agentMainModel,
            String agentSubBaseUrl,
            SecretView agentSubApiKey,
            String agentSubModel,
            String searchProvider,
            String searchBaseUrl,
            SecretView searchApiKey,
            SecretView langsmithApiKey,
            String langsmithProject
    ) {
    }

    /** 更新设置：密钥类仅非空字段会被写入（留空表示不修改）；stylePrompt/termExtractPrompt 传 null 不改、传值（含空串）即写入。 */
    public record UpdateSettingsReq(
            String groqApiKey,
            String llmBaseUrl,
            String llmApiKey,
            String llmModel,
            String dashscopeApiKey,
            String zhipuApiKey,
            String geminiBaseUrl,
            String geminiApiKey,
            String geminiModel,
            String ttsProvider,
            String ttsBaseUrl,
            String ttsApiKey,
            String ttsModel,
            String stylePrompt,
            String termExtractPrompt,
            // ---- Agent 模式 ----
            String agentMainBaseUrl,
            String agentMainApiKey,
            String agentMainModel,
            String agentSubBaseUrl,
            String agentSubApiKey,
            String agentSubModel,
            String searchProvider,
            String searchBaseUrl,
            String searchApiKey,
            String langsmithApiKey,
            String langsmithProject
    ) {
    }

    /** TTS 引擎卡片（设置页展示）：注册表信息 + 当前用户是否已配置好该引擎。 */
    public record TtsEngineVO(
            String id, String name, String desc, String tag, boolean needKey,
            String defaultBaseUrl, String defaultModel, boolean configured
    ) {
    }

    /** 搜索引擎卡片（Agent 模式设置页展示）。 */
    public record SearchEngineVO(
            String id, String name, String desc, String tag, boolean needKey,
            String defaultBaseUrl, boolean configured
    ) {
    }

    public record ChangePasswordReq(
            String oldPassword,
            String newPassword
    ) {
    }

    /** 拉取可用模型：用填写的 baseUrl/apiKey；留空则回退到已保存的有效配置。 */
    public record ListModelsReq(
            String baseUrl,
            String apiKey
    ) {
    }
}
