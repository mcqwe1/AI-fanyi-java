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
    private String geminiApiKey;

    private LocalDateTime updatedAt;
}
