package com.aifanyi.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 文本翻译历史（AI 文本翻译模式，与视频翻译任务分开）。 */
@Data
@TableName("text_translation")
public class TextTranslation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /**
     * 来源渠道：text=文本翻译 file=文本文件 ext_text=划词 ext_page=整页 ext_image=图片翻译 ext_input=输入框翻译。
     * ext_ 前缀的记录归「划词翻译」页历史，其余归「文本 AI 翻译」页历史，两边互不相见。
     */
    private String channel;

    private String targetLang;

    /** 本次翻译风格提示词（空=未指定风格） */
    private String stylePrompt;

    /** 原文摘要（列表展示用，入库时截断） */
    private String preview;

    /** 原始输入全文 */
    private String sourceText;

    /** 纯译文（按原始行结构还原） */
    private String plainTarget;

    /** 逐行对照 [{source,target}] JSON，回放对照视图用 */
    private String pairsJson;

    private String model;

    private Long elapsedMs;

    /** 疑似未翻译（译文=原文）的行数 */
    private Integer untranslatedLines;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
