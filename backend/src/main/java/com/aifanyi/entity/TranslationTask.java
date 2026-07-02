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

    private String originalFilename;
    private String videoPath;
    private String audioPath;
    private String srtPath;
    private String outputVideoPath;

    private Integer progress;
    private String errorMsg;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
