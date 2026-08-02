package com.aifanyi.agent;

import com.aifanyi.config.AifanyiProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 子 Agent 专用线程池（harness 执行编排层）。
 * <p><b>为什么必须独立于 taskExecutor</b>：后者是 core=2 / queue=50 的
 * {@code ThreadPoolTaskExecutor}，而 {@code ThreadPoolExecutor} 只在<b>队列满</b>时才扩容——
 * 50 深的队列意味着永远到不了 max=4，有效并发恒为 2。Agent 流水线本身就跑在它的线程里，
 * 再往同一个池里 fan-out，第二个并发任务就会确定性死锁。
 * <p>线程都是 I/O 阻塞型（等 LLM/搜索响应），不吃 CPU，开 8 个几乎零成本；
 * 池子开小的后果反而更隐蔽：排不上号的子 Agent 白白烧掉全局预算，
 * 事后从日志里根本分不清是模型慢还是没抢到线程（故 Trace 区分 SKIPPED 与 TIMEOUT）。
 */
@Slf4j
@Component
public class AgentExecutors {

    private final ExecutorService subAgentPool;

    public AgentExecutors(AifanyiProperties props) {
        int c = Math.max(2, props.getAgent().getConcurrency());
        this.subAgentPool = Executors.newFixedThreadPool(c, r -> {
            Thread t = new Thread(r, "agent-sub");
            t.setDaemon(true);              // 后端退出不被挂起的子 Agent 拖住
            return t;
        });
        log.info("Agent 子池初始化：{} 线程", c);
    }

    public ExecutorService subAgents() {
        return subAgentPool;
    }

    @PreDestroy
    public void shutdown() {
        subAgentPool.shutdownNow();
    }
}
