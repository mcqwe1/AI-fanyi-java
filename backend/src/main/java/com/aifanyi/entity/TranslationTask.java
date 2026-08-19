package com.aifanyi.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("translation_task")
public class TranslationTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** NORMAL / KB */
    private String mode;

    /** KB 模式所属系列项目 */
    private Long projectId;

    /** 见 TaskStatus */
    private String status;

    private String sourceLang;
    private String targetLang;
    private String asrProvider;
    private String llmModel;

    private Integer burnSubtitle;
    private Integer bilingual;

    /** VIDEO / AUDIO / TEXT（见 MediaKind；仅 VIDEO 可烧录与配音） */
    private String mediaType;

    private String originalFilename;
    /** 上传的源文件路径。名为 video 是历史原因——音频任务存音频、文本任务存 .txt，
     *  改名需迁移已发布库，故保留字段名，以 mediaType 判断实际类型。 */
    private String videoPath;
    private String audioPath;
    private String srtPath;

    /**
     * 用户随任务上传的原文字幕（srt/vtt）绝对路径。
     * 有值 = 跳过抽音频与语音识别，直接拿它的时间轴与文本去翻译。
     */
    private String subtitleSourcePath;
    private String outputVideoPath;

    /** TTS 配音：音色 / 语速 / 是否保留原声做背景 / 配音成品视频路径 */
    private String ttsVoice;
    private String ttsSpeed;
    private Integer ttsKeepOriginal;
    private String dubVideoPath;

    /**
     * 配音独立状态，与主 status 解耦：null=从未配音 / DUBBING / DONE / FAILED。
     * 配音是 DONE 任务的附加操作，其成败只动这三列，绝不影响翻译成果与主 status。
     */
    private String dubStatus;
    private Integer dubProgress;
    private String dubError;
    /** 配音成功但有需用户知晓的情况（如个别行译文过长，变速后仍与下一行重叠） */
    private String dubNotice;

    // ---- Agent 模式 ----
    /** ③ 场景推测命中的领域（逗号分隔多个 code），缓存后重试不重跑 */
    private String agentDomain;
    /** 1=本次运行有任一节点降级（搜索不可用/子Agent超时/向量库挂等），前端提示用户 */
    private Integer agentDegraded;
    /** Agent 当前细分阶段（供 2 秒轮询展示，主 status 只有粗粒度） */
    private String agentPhase;

    /** 本任务翻译风格提示词（空=不指定风格） */
    private String stylePrompt;

    /** 用户勾选的术语库（kb_project id 逗号分隔，空=不套用）；术语以提示词注入翻译 */
    private String glossaryProjectIds;

    private Integer progress;
    private String errorMsg;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
