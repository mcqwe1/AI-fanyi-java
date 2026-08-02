package com.aifanyi.agent.profile;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ⑥ 领域档案：一份档案 = 一个子 Agent 实例的全部行为定义。
 * 「一套代码 × 注入不同档案 = 不同子 Agent」，故档案数量无上限（内置与用户自定义同构）。
 * <p>userId=0 为系统内置档案（所有用户可见、不可改），>0 为该用户的自定义档案。
 * <p>source: builtin=内置 / user=用户手工创建 / draft=③ 未命中长尾时 LLM 生成的草稿（待人工精化）。
 */
@Data
@TableName("agent_profile")
public class AgentProfile {

    /** 系统内置档案的 user_id */
    public static final long SYSTEM_USER = 0L;
    /** 兜底档案：场景推测失败或未命中任何档案时使用 */
    public static final String GENERAL = "general";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    /** 领域标识，同一用户下唯一；也用作术语桶 kb_project.domain_code */
    private String domainCode;
    private String name;

    /** 判定标准：③ 主 Agent 据此判断内容是否属于本领域 */
    private String judgeCriteria;
    /** 翻译惯例：该领域的定译规则（如 IT 的 API 保留原文、医疗必须用药典名） */
    private String conventions;
    /** 搜索提示词：拼进搜索 query 提高命中权威来源的概率（分号分隔） */
    private String searchHints;
    /** few-shot 示例（JSON 数组），空则不注入 */
    private String fewShot;
    /** 命名冲突时的优先级说明（如「官方简中 > 官方繁中 > 社区通用 > 音译」） */
    private String conflictPriority;

    private String source;
    private Integer version;
    private Integer enabled;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
