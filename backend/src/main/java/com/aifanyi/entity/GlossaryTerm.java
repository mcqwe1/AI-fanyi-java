package com.aifanyi.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 术语表条目，属于某个 KB 系列项目。
 * origin: auto=模型联网抽取（待审核） / manual=人工录入 / agent=Agent 模式产出。
 * <p>Agent 模式追加的元数据列（KB 模式不写，全部可空）：置信度、状态机档位、证据、
 * 采用的译法策略、来源档案、向量同步标记。
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

    // ---- Agent 模式元数据 ----
    /** 规范化形式（小写去空白去标点），去重与查重的唯一依据；展示一律用 sourceTerm */
    private String sourceNorm;
    /** 代码算出的置信度 0~1（模型自报只是其中一个特征） */
    private java.math.BigDecimal confidence;
    /** 状态机档位：VERIFIED / PENDING / UNVERIFIED（EPHEMERAL 不落库） */
    private String status;
    /** 定译依据（权威来源片段或上下文定义句） */
    private String evidence;
    /** 译法策略：AUTHORITATIVE/KEEP_ORIGINAL/TRANSLITERATE/FREE/SOUND_MEANING/COINAGE */
    private String strategy;
    /** 产出该词的领域档案 code */
    private String profileCode;
    /** 0=未向量化 1=已同步；异步向量化崩溃后据此补偿 */
    private Integer vectorStatus;
    /** 最后一次提出/确认该词的任务 id；跨任务确认飞轮据此防「同一任务重试刷分」 */
    private Long lastTaskId;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
