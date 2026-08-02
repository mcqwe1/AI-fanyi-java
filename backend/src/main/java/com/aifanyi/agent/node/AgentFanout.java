package com.aifanyi.agent.node;

import com.aifanyi.agent.AgentBudget;
import com.aifanyi.agent.AgentExecutors;
import com.aifanyi.agent.Deadline;
import com.aifanyi.agent.llm.AgentJsonClient;
import com.aifanyi.agent.model.DegradeReason;
import com.aifanyi.agent.model.SubAgentResult;
import com.aifanyi.agent.model.SubAgentStatus;
import com.aifanyi.agent.profile.AgentProfile;
import com.aifanyi.agent.search.SearchProviderFactory;
import com.aifanyi.agent.trace.TraceRecorder;
import com.aifanyi.config.AifanyiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntConsumer;

/**
 * ④ 扇出编排：档案 → N 个子 Agent 并行执行（结构化并发 + 全局 deadline）。
 *
 * <p><b>为什么手写而不用 StructuredTaskScope</b>：它在 Java 21 是预览 API，
 * 启用 --enable-preview 会波及 pom、spring-boot 重打包、build-portable.ps1 与 start.bat，
 * 且产物拒绝在不同小版本 JRE 上运行——对便携分发是硬伤。
 * finally 里统一 cancel 能给出同样的「无任务逃逸出作用域」保证，代价是 3 行。
 *
 * <p><b>绝不抛异常</b>：全部子 Agent 失败也只是返回空术语 + degraded=true，
 * 翻译照常进行（退化为普通模式的质量）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentFanout {

    private final AgentExecutors executors;
    private final AgentJsonClient llm;
    private final SearchProviderFactory searchFactory;
    private final TraceRecorder trace;
    private final AifanyiProperties props;

    /** 扇出结果汇总。 */
    public record FanoutResult(List<SubAgentResult> results, boolean degraded, long totalTokens) {

        public static FanoutResult of(List<SubAgentResult> rs) {
            boolean deg = rs.stream().anyMatch(r -> r.status() != SubAgentStatus.DONE);
            long tk = rs.stream().mapToLong(SubAgentResult::totalTokens).sum();
            return new FanoutResult(rs, deg, tk);
        }

        public int termCount() {
            return results.stream().mapToInt(r -> r.terms().size()).sum();
        }
    }

    /**
     * 并行跑全部子 Agent。
     *
     * @param profiles 已按匹配度筛选并封顶的档案列表
     * @param base     子 Agent 上下文模板（各实例只换 profile）
     * @param global   全局 deadline
     * @param progress 完成计数回调（可为 null）
     */
    public FanoutResult run(List<AgentProfile> profiles, SubAgentTask.SubAgentContext base,
                            Deadline global, TraceRecorder.Buffer traceBuf, IntConsumer progress) {
        if (profiles == null || profiles.isEmpty()) {
            return FanoutResult.of(List.of());
        }
        AifanyiProperties.Agent cfg = props.getAgent();
        List<SubAgentTask> tasks = new ArrayList<>(profiles.size());
        List<Future<SubAgentResult>> futures = new ArrayList<>(profiles.size());

        try {
            for (AgentProfile p : profiles) {
                // 子 deadline = min(单体上限, 全局剩余)：全局兜底优先于单体上限
                Deadline sub = Deadline.earliestOf(global, Duration.ofSeconds(cfg.getSubAgentTimeoutSec()));
                AgentBudget budget = AgentBudget.of(sub, 2,
                        cfg.getMaxToolsPerAgent(), cfg.getMaxSearchPerAgent());
                SubAgentTask t = new SubAgentTask(base.withProfile(p), budget, llm,
                        searchFactory.get(base.searchCfg().wire()), trace, traceBuf);
                tasks.add(t);                                   // ★ 先持有引用
                futures.add(executors.subAgents().submit(t));    // ★ 再提交
            }

            List<SubAgentResult> results = new ArrayList<>(tasks.size());
            for (int i = 0; i < futures.size(); i++) {
                Future<SubAgentResult> f = futures.get(i);
                long left = global.remainingMs();
                if (left <= 0) {
                    // 全局到点：没轮到的直接标 SKIPPED（与 TIMEOUT 区分，便于诊断池子是否开小了）
                    f.cancel(true);
                    SubAgentResult snap = tasks.get(i).snapshot();
                    results.add(snap.terms().isEmpty()
                            ? SubAgentResult.skipped(snap.profileCode(), DegradeReason.NOT_STARTED)
                            : snap.degradedBy(DegradeReason.GLOBAL_DEADLINE));
                    continue;
                }
                try {
                    results.add(f.get(left, TimeUnit.MILLISECONDS));
                } catch (TimeoutException te) {
                    f.cancel(true);
                    // 取消后仍能从 task 对象拿到已提交的部分结果
                    results.add(tasks.get(i).snapshot().degradedBy(DegradeReason.TIMEOUT));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    results.add(tasks.get(i).snapshot().degradedBy(DegradeReason.CANCELLED));
                } catch (Exception ee) {
                    // SubAgentTask.call() 契约是不抛，走到这里说明是 Error 级别，仍不失败整体
                    results.add(tasks.get(i).snapshot().degradedBy(DegradeReason.ERROR));
                }
                if (progress != null) {
                    progress.accept(i + 1);
                }
            }
            logSummary(results);
            return FanoutResult.of(results);
        } finally {
            // 结构化并发的「作用域退出即全部结束」语义：保证无 Future 逃逸
            for (Future<?> f : futures) {
                f.cancel(true);
            }
        }
    }

    private void logSummary(List<SubAgentResult> rs) {
        int done = 0, partial = 0, failed = 0, skipped = 0, terms = 0;
        for (SubAgentResult r : rs) {
            switch (r.status()) {
                case DONE -> done++;
                case PARTIAL -> partial++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
            }
            terms += r.terms().size();
        }
        log.info("子Agent 扇出完成：{} 成功 / {} 部分 / {} 失败 / {} 跳过，共 {} 条候选术语",
                done, partial, failed, skipped, terms);
    }
}
