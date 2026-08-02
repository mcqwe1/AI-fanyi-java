package com.aifanyi.agent.model;

import java.util.List;

/**
 * 一个子 Agent 的运行结果。
 * <p>关键契约：<b>即使超时/失败也带回已提交的部分结果</b>（架构：超限→已有结果收尾）。
 * 降级表里那句「超时→返回空」与此矛盾，按正文取部分结果——已经付出的 token 不该白扔。
 */
public record SubAgentResult(String profileCode, SubAgentStatus status, List<TermDraft> terms,
                             Step lastStep, DegradeReason degradeReason, long elapsedMs,
                             long totalTokens, String profileProposalJson) {

    public static SubAgentResult skipped(String profileCode, DegradeReason why) {
        return new SubAgentResult(profileCode, SubAgentStatus.SKIPPED, List.of(),
                Step.INIT, why, 0, 0, null);
    }

    /** 打上降级标记（外部超时时由 orchestrator 调用）。 */
    public SubAgentResult degradedBy(DegradeReason why) {
        SubAgentStatus st = terms.isEmpty() ? SubAgentStatus.FAILED : SubAgentStatus.PARTIAL;
        return new SubAgentResult(profileCode, st, terms, lastStep, why, elapsedMs,
                totalTokens, profileProposalJson);
    }

    public boolean degraded() {
        return status != SubAgentStatus.DONE;
    }
}
