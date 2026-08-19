package com.aifanyi.agent;

import com.aifanyi.agent.trace.AgentTrace;
import com.aifanyi.common.R;
import com.aifanyi.entity.GlossaryTerm;
import com.aifanyi.entity.TranslationTask;
import com.aifanyi.mapper.AgentTraceMapper;
import com.aifanyi.mapper.GlossaryTermMapper;
import com.aifanyi.security.SecurityUtils;
import com.aifanyi.service.TaskService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 运行详情（harness 评估与观测层的对外出口）。
 * 前端运行详情面板据此展示阶段、Trace 时间线与抽出的术语。
 * 2026-08 需求改版：删除「确认术语」接口——置信度 ≥0.8 自动入库、以下不落库，
 * 用户不再感知置信度，也无需手动确认。
 */
@RestController
@RequestMapping("/api/tasks/{id}/agent")
@RequiredArgsConstructor
public class AgentController {

    private final TaskService taskService;
    private final AgentTraceMapper traceMapper;
    private final GlossaryTermMapper termMapper;

    /** 一条 Trace（前端时间线用）。 */
    public record TraceVO(Integer seq, String node, String profileCode, String inputDigest,
                          String outputDigest, Long elapsedMs, Integer totalTokens,
                          String stopReason, Integer degraded, LocalDateTime createdAt) {
    }

    /** 一条术语（含 Agent 元数据）。 */
    public record TermVO(Long id, String sourceTerm, String targetTerm, String status,
                         java.math.BigDecimal confidence, String strategy, String evidence,
                         String profileCode, String note, Integer enabled) {
    }

    public record AgentRunVO(String status, Integer progress, String agentPhase, String agentDomain,
                             Integer agentDegraded, String errorMsg,
                             long totalTokens, List<TraceVO> traces, List<TermVO> terms) {
    }

    /** 运行详情：任务状态 + Trace 时间线 + 本次任务相关术语。 */
    @GetMapping
    public R<AgentRunVO> detail(@PathVariable Long id) {
        Long uid = SecurityUtils.currentUserId();
        TranslationTask task = taskService.getOwned(id, uid);

        List<AgentTrace> traces = traceMapper.selectList(Wrappers.<AgentTrace>lambdaQuery()
                .eq(AgentTrace::getTaskId, id)
                .orderByAsc(AgentTrace::getSeq));
        long tokens = traces.stream()
                .mapToLong(t -> t.getTotalTokens() == null ? 0 : t.getTotalTokens()).sum();

        // 术语取该任务所属术语桶里由 Agent 产出的条目（按置信度降序）
        List<TermVO> terms = List.of();
        if (task.getProjectId() != null) {
            terms = termMapper.selectList(Wrappers.<GlossaryTerm>lambdaQuery()
                            .eq(GlossaryTerm::getProjectId, task.getProjectId())
                            .orderByDesc(GlossaryTerm::getConfidence))
                    .stream().map(this::toTermVO).toList();
        }

        return R.ok(new AgentRunVO(task.getStatus(), task.getProgress(), task.getAgentPhase(),
                task.getAgentDomain(), task.getAgentDegraded(), task.getErrorMsg(),
                tokens, traces.stream().map(this::toTraceVO).toList(), terms));
    }

    private TraceVO toTraceVO(AgentTrace t) {
        return new TraceVO(t.getSeq(), t.getNode(), t.getProfileCode(), t.getInputDigest(),
                t.getOutputDigest(), t.getElapsedMs(), t.getTotalTokens(),
                t.getStopReason(), t.getDegraded(), t.getCreatedAt());
    }

    private TermVO toTermVO(GlossaryTerm g) {
        return new TermVO(g.getId(), g.getSourceTerm(), g.getTargetTerm(), g.getStatus(),
                g.getConfidence(), g.getStrategy(), g.getEvidence(), g.getProfileCode(),
                g.getNote(), g.getEnabled());
    }
}
