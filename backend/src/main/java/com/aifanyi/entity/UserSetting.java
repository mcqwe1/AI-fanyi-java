package com.aifanyi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户设置：各平台 API 密钥等（每用户一行，user_id 为主键）。
 */
@Data
@TableName("user_setting")
public class UserSetting {

    @TableId(type = IdType.INPUT)
    private Long userId;

    private String groqApiKey;
    private String llmBaseUrl;
    private String llmApiKey;
    private String llmModel;
    private String dashscopeApiKey;
    private String zhipuApiKey;
    private String geminiBaseUrl;
    private String geminiApiKey;
    private String geminiModel;

    /** 默认翻译风格提示词（三个翻译模式默认带出，任务可临时覆盖；空=无默认风格） */
    private String stylePrompt;

    private LocalDateTime updatedAt;
}
