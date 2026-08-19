package com.aifanyi.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 已配置的模型服务（「设置 → API 配置 → 大语言模型」的多服务商列表）。
 * is_default=1 且 enabled=1 的行是全局翻译当前使用的服务；
 * 没有任何行时回落到 user_setting 里的旧 llm_* 单配置（老用户无感迁移）。
 */
@Data
@TableName("model_service")
public class ModelService {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 目前只有 llm（表结构预留 asr/tts 扩展位） */
    private String category;

    /** 服务商 id（见 LlmProviders 注册表，如 deepseek/claude/custom-openai） */
    private String provider;

    /** 通信协议：openai / claude / deepl / google-mt / ms-mt */
    private String protocol;

    private String baseUrl;
    private String apiKey;

    /** LLM 协议=模型名；微软翻译=区域(region)；谷歌/DeepL 不使用 */
    private String model;

    /** 请求超时（秒） */
    private Integer timeoutSec;

    /** 并发批次数：该服务同时在途的翻译请求数。null/0 = 用 aifanyi.llm.concurrency 默认值 */
    private Integer concurrency;

    private Integer enabled;
    private Integer isDefault;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
