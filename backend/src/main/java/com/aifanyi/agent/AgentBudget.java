package com.aifanyi.agent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单个子 Agent 的资源预算（harness 约束层的核心）。
 * <p>架构要求「硬约束(代码兜底)：工具≤3次 │ 墙钟30s │ 超限→已有结果收尾」——
 * 关键在<b>代码兜底</b>：绝不靠在 prompt 里写「你只能搜 2 次」然后指望模型守规矩。
 * <p>三个计数器各管一件事，语义互不重叠：
 * <ul>
 *   <li>{@code llm} —— DAG 里的 LLM 步骤数（固定 2 步：提取、定译）</li>
 *   <li>{@code requests} —— 实际 HTTP 请求数（含解析失败后的重试），上限高于 llm</li>
 *   <li>{@code search}/{@code tools} —— 外部工具调用；搜索 ≤2 次「不同查询」，
 *       多出的 1 次 tool 余量留给单次失败重试，这才是架构那句话的本意</li>
 * </ul>
 */
public final class AgentBudget {

    private final Deadline deadline;
    private final int maxLlm;
    private final int maxRequests;
    private final int maxTools;
    private final int maxSearch;

    private final AtomicInteger llmUsed = new AtomicInteger();
    private final AtomicInteger requestsUsed = new AtomicInteger();
    private final AtomicInteger toolsUsed = new AtomicInteger();
    private final AtomicInteger searchUsed = new AtomicInteger();
    /** 搜索封口：DAG 走过搜索步骤后置位，之后任何搜索请求一律拒绝 */
    private volatile boolean searchSealed;

    public AgentBudget(Deadline deadline, int maxLlm, int maxTools, int maxSearch) {
        this.deadline = deadline;
        this.maxLlm = maxLlm;
        // 请求上限 = LLM 步骤数 × 2：每步最多重试一次（解析失败/网络错），再多就是端点有问题
        this.maxRequests = maxLlm * 2;
        this.maxTools = maxTools;
        this.maxSearch = maxSearch;
    }

    public static AgentBudget of(Deadline deadline, int maxLlm, int maxTools, int maxSearch) {
        return new AgentBudget(deadline, maxLlm, maxTools, maxSearch);
    }

    public Deadline deadline() {
        return deadline;
    }

    public long remainingMs() {
        return deadline.remainingMs();
    }

    /** 是否还能开启一个新的 LLM 步骤（不含重试）。 */
    public boolean tryLlmStep() {
        if (deadline.expired()) {
            return false;
        }
        return bumpIfBelow(llmUsed, maxLlm);
    }

    /** 是否还能发一次 HTTP 请求（含重试）。步骤已批准后每次实际发送前调用。 */
    public boolean tryRequest() {
        if (deadline.expired()) {
            return false;
        }
        return bumpIfBelow(requestsUsed, maxRequests);
    }

    /** 是否还能搜一次。同时校验 tools 总额、search 额度、封口标记与截止时刻。 */
    public boolean trySearch() {
        if (searchSealed || deadline.expired()) {
            return false;
        }
        if (searchUsed.get() >= maxSearch || toolsUsed.get() >= maxTools) {
            return false;
        }
        // 先占 tool 名额再占 search 名额；任一失败即回滚，避免并发下超发
        if (!bumpIfBelow(toolsUsed, maxTools)) {
            return false;
        }
        if (!bumpIfBelow(searchUsed, maxSearch)) {
            toolsUsed.decrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * 封死搜索通道。DAG 离开搜索步骤后立即调用——
     * 「查不到 = 换策略信号，而非继续搜」是架构的明确要求，这里让它成为物理事实。
     */
    public void sealSearch() {
        this.searchSealed = true;
    }

    private static boolean bumpIfBelow(AtomicInteger counter, int max) {
        while (true) {
            int cur = counter.get();
            if (cur >= max) {
                return false;
            }
            if (counter.compareAndSet(cur, cur + 1)) {
                return true;
            }
        }
    }

    public int llmUsed() {
        return llmUsed.get();
    }

    public int searchUsed() {
        return searchUsed.get();
    }

    public int toolsUsed() {
        return toolsUsed.get();
    }

    @Override
    public String toString() {
        return String.format("Budget(llm=%d/%d, req=%d/%d, search=%d/%d, tools=%d/%d, left=%dms%s)",
                llmUsed.get(), maxLlm, requestsUsed.get(), maxRequests,
                searchUsed.get(), maxSearch, toolsUsed.get(), maxTools,
                deadline.remainingMs(), searchSealed ? ", sealed" : "");
    }
}
