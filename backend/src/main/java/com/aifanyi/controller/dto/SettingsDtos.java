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
            String stylePrompt
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
            String geminiBaseUrl,
            String geminiApiKey,
            String geminiModel,
            String stylePrompt
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
