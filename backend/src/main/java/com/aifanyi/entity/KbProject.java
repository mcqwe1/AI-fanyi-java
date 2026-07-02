package com.aifanyi.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * KB 系列项目：一系列视频共用一张术语表。
 */
@Data
@TableName("kb_project")
public class KbProject {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String name;
    private String sourceLang;
    private String targetLang;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
