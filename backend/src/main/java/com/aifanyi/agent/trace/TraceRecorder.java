package com.aifanyi.agent.trace;

import com.aifanyi.mapper.AgentTraceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Trace 记录器（harness 评估与观测层）。
 * <p>每次 LLM / 工具调用记一行，前端运行详情面板据此画时间线，出问题时不必翻日志猜。
 * <p><b>缓冲 + 阶段边界落库</b>：H2 是文件库且每语句自动提交，逐条 insert 等于逐条 fsync；
 * 但「运行现场」要求跑动中就能看到条目，所以在每个有展示价值的节点完成时 flush
 * 一小批（场景推测后、每次搜索后、专家每步后、仲裁后……），而非攒到任务结束。
 * <p><b>绝不影响主流程</b>：记录与落库的任何异常都吞掉——观测手段坏了不该让翻译失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraceRecorder {

    private final AgentTraceMapper mapper;
    private final LangSmithExporter langSmith;

    /** 一次任务运行的 trace 缓冲区。非线程安全的部分都加了锁，因为子 Agent 是并发的。 */
    public static final class Buffer {
        private final Long taskId;
        private final List<AgentTrace> rows = new ArrayList<>();
        private final AtomicInteger seq = new AtomicInteger();
        // 总账单独累计而不从 rows 汇总——中途 flush 会清空 rows，账不能跟着丢
        private final java.util.concurrent.atomic.AtomicLong tokens =
                new java.util.concurrent.atomic.AtomicLong();
        private volatile boolean degradedSeen;
        /** LangSmith 上报上下文；null=未启用。挂在 Buffer 上是因为它已经随调用链传遍所有节点 */
        public volatile LangSmithExporter.Ctx lsCtx;

        Buffer(Long taskId) {
            this.taskId = taskId;
        }

        public Long taskId() {
            return taskId;
        }

        /** 是否有任一条 degraded——用于给任务打整体降级标记。 */
        public boolean anyDegraded() {
            return degradedSeen;
        }

        public int size() {
            synchronized (rows) {
                return rows.size();
            }
        }

        /** 累计 token 消耗（成本观测）。 */
        public long totalTokens() {
            return tokens.get();
        }
    }

    public Buffer newBuffer(Long taskId) {
        return new Buffer(taskId);
    }

    /**
     * 记一条 trace 到缓冲区（不落库）。<b>只写本地摘要</b>——运行现场面板用，截断存储；
     * LangSmith 由 {@link #llmFull}（LLM 全文）和 {@link #stepFull}（关键步骤明细）显式上报，
     * 摘要串不再自动镜像上云（「extract:5778字」这种记账缩写在云端没人看得懂）。
     *
     * @param node         节点名：SCENE / SUBAGENT / SEARCH / ARBITRATE / TERMS / TRANSLATE / VECTOR
     * @param profileCode  子 Agent 的档案 code，非子 Agent 节点传 null
     * @param stopReason   OK / TIMEOUT / BUDGET / PARSE_FAIL / HTTP_xxx / SKIPPED / CANCELLED
     */
    public void record(Buffer buf, String node, String profileCode, String input, String output,
                       long elapsedMs, long promptTk, long completionTk, long totalTk,
                       String stopReason, boolean degraded) {
        if (buf == null) {
            return;
        }
        try {
            AgentTrace t = new AgentTrace();
            t.setTaskId(buf.taskId);
            t.setNode(node);
            t.setProfileCode(profileCode);
            t.setSeq(buf.seq.incrementAndGet());
            t.setInputDigest(truncate(input, 500));
            t.setOutputDigest(truncate(output, 2000));
            t.setElapsedMs(elapsedMs);
            t.setPromptTokens((int) promptTk);
            t.setCompletionTokens((int) completionTk);
            t.setTotalTokens((int) totalTk);
            t.setStopReason(stopReason);
            t.setDegraded(degraded ? 1 : 0);
            synchronized (buf.rows) {
                buf.rows.add(t);
            }
            buf.tokens.addAndGet(totalTk);
            if (degraded) {
                buf.degradedSeen = true;
            }
        } catch (Exception e) {
            log.debug("记录 trace 失败（忽略）: {}", e.toString());
        }
    }

    /** 简化版：无 token 统计的节点（搜索、仲裁、向量等）。 */
    public void record(Buffer buf, String node, String profileCode, String input, String output,
                       long elapsedMs, String stopReason, boolean degraded) {
        record(buf, node, profileCode, input, output, elapsedMs, 0, 0, 0, stopReason, degraded);
    }

    /**
     * LLM 调用全文上报（仅 LangSmith，agent_trace 里仍只存摘要）。
     * <p>agent_trace 是产品功能（运行详情面板），存摘要够用且省库；
     * LangSmith 是开发调试，要的就是完整提示词与完整返回。两者定位不同，分开喂。
     */
    public void llmFull(Buffer buf, String name, String model, String system, String user,
                        String output, String stopReason, long elapsedMs,
                        long promptTk, long completionTk) {
        if (buf == null || langSmith == null) {
            return;
        }
        try {
            langSmith.llm(buf.lsCtx, name, model, system, user, output, stopReason,
                    elapsedMs, promptTk, completionTk);
        } catch (Exception e) {
            log.debug("LangSmith LLM 上报失败（忽略）: {}", e.toString());
        }
    }

    /**
     * 关键步骤的结构化上报（仅 LangSmith，本地 agent_trace 不重复存）。
     * <p>与 record 的分工：record 存本地摘要（截断），这里给 LangSmith 喂<b>看得懂的完整内容</b>——
     * 仲裁后的术语表全文、搜索命中明细、注入翻译的术语清单。字段名用人话，长值由导出层统一截断。
     */
    public void stepFull(Buffer buf, String name, Map<String, Object> inputs,
                         Map<String, Object> outputs, long elapsedMs, boolean degraded) {
        if (buf == null || langSmith == null) {
            return;
        }
        try {
            langSmith.step(buf.lsCtx, name, inputs, outputs, elapsedMs, degraded);
        } catch (Exception e) {
            log.debug("LangSmith 步骤上报失败（忽略）: {}", e.toString());
        }
    }

    /** 保序字段表（Map.of 不保序，LangSmith 展示会乱跳）。用法：fields(k1, v1, k2, v2, …)。 */
    public static Map<String, Object> fields(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /**
     * 批量落库并清空缓冲。<b>可在跑动中反复调用</b>——每个有展示价值的节点完成时来一次，
     * 前端轮询就能实时看到现场（一次 1~3 行，事务包住，H2 开销可忽略）。
     * 并发安全：多线程同时 flush 时各自取走一批，行不会重复落库。
     */
    @Transactional
    public void flush(Buffer buf) {
        if (buf == null) {
            return;
        }
        List<AgentTrace> batch;
        synchronized (buf.rows) {
            if (buf.rows.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(buf.rows);
            buf.rows.clear();
        }
        if (mapper == null) {
            return;                    // 单测环境无 mapper：只清缓冲
        }
        try {
            for (AgentTrace t : batch) {
                mapper.insert(t);
            }
            log.debug("Trace 落库 {} 条（任务 {}）", batch.size(), buf.taskId);
        } catch (Exception e) {
            // 观测失败绝不影响翻译成果
            log.warn("Trace 落库失败（忽略，不影响任务）: {}", e.toString());
        }
    }

    /** 重跑前清掉上一轮的 trace——重试复用同一 taskId，不清会新旧两轮混排在时间线里。 */
    public void reset(Long taskId) {
        if (mapper == null || taskId == null) {
            return;
        }
        try {
            mapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers.<AgentTrace>lambdaQuery()
                    .eq(AgentTrace::getTaskId, taskId));
        } catch (Exception e) {
            log.debug("清理旧 trace 失败（忽略）: {}", e.toString());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
