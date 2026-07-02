package com.aifanyi.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 术语表条目，属于某个 KB 系列项目。
 * origin: auto=模型联网抽取（待审核） / manual=人工录入。
 */
@Data
@TableName("glossary_term")
public class GlossaryTerm {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private String sourceTerm;
    private String targetTerm;
    private String category;
    private String note;
    private String origin;
    private Integer enabled;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
