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
            String ttsProvider,
            String ttsBaseUrl,
            SecretView ttsApiKey,
            String ttsModel,
            String stylePrompt,
            // ---- Agent 模式（全能 AI 翻译）----
            String agentTranslateBaseUrl,
            SecretView agentTranslateApiKey,
            String agentTranslateModel,
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

    /** 更新设置：密钥类仅非空字段会被写入（留空表示不修改）；stylePrompt 传 null 不改、传值（含空串）即写入。 */
    public record UpdateSettingsReq(
            String groqApiKey,
            String llmBaseUrl,
            String llmApiKey,
            String llmModel,
            String dashscopeApiKey,
            String zhipuApiKey,
            String ttsProvider,
            String ttsBaseUrl,
            String ttsApiKey,
            String ttsModel,
            String stylePrompt,
            // ---- Agent 模式 ----
            String agentTranslateBaseUrl,
            String agentTranslateApiKey,
            String agentTranslateModel,
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

    /** 拉取可用模型：用填写的 baseUrl/apiKey；留空则回退到已保存的有效配置。protocol 决定鉴权头（claude=x-api-key）。 */
    public record ListModelsReq(
            String baseUrl,
            String apiKey,
            String protocol
    ) {
    }

    // ---- 已配置的模型服务（多服务商） ----

    /** 服务商注册表项（服务商下拉）。 */
    public record LlmProviderVO(
            String id, String name, String protocol,
            String defaultBaseUrl, String defaultModel,
            String keyHint, String docsUrl, String note,
            boolean needModel, boolean canListModels, String modelLabel, boolean custom
    ) {
    }

    /** 已配置的模型服务行。 */
    public record ModelServiceVO(
            Long id, String provider, String providerName, String protocol,
            String baseUrl, SecretView apiKey, String model, int timeoutSec,
            int concurrency,
            boolean enabled, boolean isDefault, java.time.LocalDateTime updatedAt
    ) {
    }

    /** 新增/更新/测试模型服务。更新与测试时 apiKey 留空=用已存的（id 必传）。 */
    public record SaveModelServiceReq(
            Long id,
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            Integer timeoutSec,
            Integer concurrency,
            Boolean makeDefault
    ) {
    }

    /**
     * 语音识别引擎卡片。
     *
     * @param speedFactor 实测倍速（视频时长 ÷ 转写耗时）。前端拿它 × 媒体时长
     *                    算「预计耗时」，让用户提交前就知道要等多久。0 = 无法预估。
     */
    public record AsrEngineVO(
            String id, String name, String desc, String tag, boolean needKey, boolean configured,
            double speedFactor
    ) {
    }

    /** 启用/停用某个模型服务。 */
    public record ToggleServiceReq(Boolean enabled) {
    }
}
