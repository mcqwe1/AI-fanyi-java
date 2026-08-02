package com.aifanyi.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * KB 系列项目：一系列视频共用一张术语表。
 * Agent 模式复用本表作术语桶——每个「用户×领域」自动建一个（domainCode 非空、autoCreated=1），
 * 从而免费复用现有术语库管理页的增删改查与跨库移动。
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

    /** Agent 术语桶的领域标识；普通 KB 项目为 null。查桶按 (userId, domainCode)，不按 name */
    private String domainCode;
    /** 1=Agent 自动创建的术语桶（用户可改名，改名不影响定位） */
    private Integer autoCreated;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
