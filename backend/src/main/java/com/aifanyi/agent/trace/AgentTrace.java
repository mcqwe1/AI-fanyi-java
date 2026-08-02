package com.aifanyi.agent.trace;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ⑩ 横切 Trace：每次 LLM / 工具调用落一行。
 * 这是 harness 的「评估与观测」层——没有它，Agent 出问题只能靠翻日志猜。
 * <p>输入只存摘要（避免整段转写塞进库），输出存截断后的原文便于定位解析问题。
 */
@Data
@TableName("agent_trace")
public class AgentTrace {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;
    /** 节点：SCENE / SUBAGENT / SEARCH / ARBITRATE / TERMS / TRANSLATE / VECTOR */
    private String node;
    /** 子 Agent 对应的档案 code（非子 Agent 节点为 null） */
    private String profileCode;
    /** 任务内递增序号，保证前端时间线顺序稳定（同毫秒落库时 created_at 无法区分） */
    private Integer seq;

    private String inputDigest;
    private String outputDigest;
    private Long elapsedMs;

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

    /** 终止原因：OK / TIMEOUT / BUDGET / PARSE_FAIL / HTTP_FAIL / SKIPPED / CANCELLED */
    private String stopReason;
    private Integer degraded;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
