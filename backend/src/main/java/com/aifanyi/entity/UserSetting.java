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

    /** TTS 配音引擎：edge / siliconflow / openai（见 TtsEngines；空=未选择） */
    private String ttsProvider;

    /** TTS 引擎配置（OpenAI 兼容 /audio/speech 端点；edge 引擎不使用） */
    private String ttsBaseUrl;
    private String ttsApiKey;
    private String ttsModel;

    /** 默认翻译风格提示词（三个翻译模式默认带出，任务可临时覆盖；空=无默认风格） */
    private String stylePrompt;

    // ---- Agent 模式（实验功能）----
    /** 全能AI翻译的翻译模型：空=用默认大语言模型服务 */
    private String agentTranslateBaseUrl;
    private String agentTranslateApiKey;
    private String agentTranslateModel;

    /** 主 Agent（场景推测/仲裁）模型配置：判断力要求高，可配强模型 */
    private String agentMainBaseUrl;
    private String agentMainApiKey;
    private String agentMainModel;

    /** 子 Agent（批量抽词）模型配置：调用量大，可配便宜模型 */
    private String agentSubBaseUrl;
    private String agentSubApiKey;
    private String agentSubModel;

    /** 联网搜索引擎（见 SearchEngines；空/none=不联网，走无搜索降级路径） */
    private String searchProvider;
    private String searchBaseUrl;
    private String searchApiKey;

    /** Qdrant 向量库地址（Agent 模式用；空=用内置默认） */
    private String vectorUrl;

    /** LangSmith 云端观测（可选调试）：Key 留空即完全不上报 */
    private String langsmithApiKey;
    /** LangSmith 项目名；空=默认 aifanyi */
    private String langsmithProject;

    private LocalDateTime updatedAt;
}
