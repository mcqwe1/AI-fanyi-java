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
            SecretView geminiApiKey
    ) {
    }

    /** 更新设置：仅非空字段会被写入（留空表示不修改）。 */
    public record UpdateSettingsReq(
            String groqApiKey,
            String llmBaseUrl,
            String llmApiKey,
            String llmModel,
            String dashscopeApiKey,
            String zhipuApiKey,
            String geminiApiKey
    ) {
    }

    public record ChangePasswordReq(
            String oldPassword,
            String newPassword
    ) {
    }
}
