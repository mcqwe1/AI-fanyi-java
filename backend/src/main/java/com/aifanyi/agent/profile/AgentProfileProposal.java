package com.aifanyi.agent.profile;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ④→⑥ 档案修正提议：子 Agent 在真实搜索后发现档案里的惯例有误或搜索词不好用，
 * 提交修正建议（惯例纠错 / 搜索词增删），使档案「越用越准」。
 * <p><b>永不自动应用</b>——一次糟糕的搜索不该永久污染档案，必须人工确认。
 */
@Data
@TableName("agent_profile_proposal")
public class AgentProfileProposal {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long profileId;
    /** 提出该建议的任务，便于回溯上下文 */
    private Long taskId;
    private Long userId;

    /** convention=惯例纠错 / add_hint=新增搜索词 / drop_hint=删除无效搜索词 */
    private String kind;
    /** 建议内容（JSON） */
    private String payload;
    /** PENDING / ACCEPTED / REJECTED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
