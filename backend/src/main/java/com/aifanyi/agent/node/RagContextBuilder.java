package com.aifanyi.agent.node;

import com.aifanyi.agent.model.TermBundle;
import com.aifanyi.config.AifanyiProperties;
import com.aifanyi.llm.LlmConfig;
import com.aifanyi.llm.OpenAiTranslator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * ⑧ RAG 翻译：术语注入 + 滚动窗口（harness 上下文管理层）。
 *
 * <p><b>分组串行、组间并发</b>——这是质量与速度的折中：
 * 架构要求滚动窗口带「前 3~5 句原<b>+译</b>」，而带译文意味着每批都要等上一批的输出，
 * 会把现有 8 路并发退化为全串行（翻译本就是全流程最慢的一段，约 8 倍墙钟回归）。
 * 折中做法是把批次分组，<b>组内串行</b>（完整上下文，指代/称谓/语气连续），
 * <b>组间并发</b>（复用现有并发度）。只在组边界丢上下文，速度接近现状、质量接近全串行。
 *
 * <p>术语注入无需额外机制：OpenAiTranslator 的 buildSystemPrompt 已按批做
 * {@code joined.contains(term)} 过滤，这里只是传入更丰富的映射（历史 + 本次产出）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagContextBuilder {

    private final OpenAiTranslator translator;
    private final AifanyiProperties props;

    /** 翻译产出：译文（与原文等长，未译处保留原文）+ 未译行号（0 基、升序）。 */
    public record RagResult(List<String> targets, List<Integer> missingLines) {
    }

    /**
     * 翻译全部分片。
     *
     * @param sources    原文行（与分片一一对应）
     * @param bundle     本次可用术语（历史 + 新产出 + EPHEMERAL）
     * @param onProgress 已完成批次数回调，可为 null
     * @return 与 sources 等长、顺序一致的译文 + 未译行号（调用方据此标降级、告知用户）
     */
    public RagResult translate(List<String> sources, String targetLang, LlmConfig cfg,
                               TermBundle bundle, String stylePrompt,
                               java.util.function.IntConsumer onProgress) {
        if (sources == null || sources.isEmpty()) {
            return new RagResult(List.of(), List.of());
        }
        AifanyiProperties.Agent acfg = props.getAgent();
        int batchSize = Math.max(1, cfg.batchSize());
        int groupSize = Math.max(1, acfg.getTranslateGroupSize());
        int overlap = Math.max(0, acfg.getContextOverlap());

        // 切批
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < sources.size(); i += batchSize) {
            batches.add(sources.subList(i, Math.min(i + batchSize, sources.size())));
        }
        // 分组：组内串行、组间并发
        List<List<Integer>> groups = new ArrayList<>();
        for (int i = 0; i < batches.size(); i += groupSize) {
            List<Integer> g = new ArrayList<>();
            for (int j = i; j < Math.min(i + groupSize, batches.size()); j++) {
                g.add(j);
            }
            groups.add(g);
        }

        java.util.Map<String, String> glossary = bundle == null ? java.util.Map.of() : bundle.map();
        String[][] out = new String[batches.size()][];
        // 未译行（全局 0 基行号）：批内漏行 + 整批失败都汇到这里，调用方如实告知用户
        List<Integer> missing = Collections.synchronizedList(new ArrayList<>());
        int concurrency = Math.max(1, Math.min(cfg.concurrency(), groups.size()));
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "agent-translate");
            t.setDaemon(true);
            return t;
        });
        long t0 = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();

        try {
            List<Future<?>> futures = new ArrayList<>(groups.size());
            for (List<Integer> group : groups) {
                futures.add(pool.submit(() -> {
                    // ── 组内串行：每批携带上一批的原文+译文作滚动窗口 ──
                    List<String> priorSrc = null;
                    List<String> priorTgt = null;
                    for (int idx : group) {
                        List<String> batch = batches.get(idx);
                        List<String> translated;
                        try {
                            OpenAiTranslator.BatchOutcome oc = translator.translateBatchWithContext(
                                    batch, targetLang, cfg, glossary, stylePrompt, priorSrc, priorTgt);
                            translated = oc.lines();
                            for (int mi : oc.missing()) {
                                missing.add(idx * batchSize + mi);
                            }
                        } catch (Exception e) {
                            // 单批失败降级为原文，绝不让整篇崩（照 OpenAiTranslator 的哲学）
                            log.warn("第 {} 批翻译失败，保留原文: {}", idx, e.getMessage());
                            translated = new ArrayList<>(batch);
                            for (int k = 0; k < batch.size(); k++) {
                                missing.add(idx * batchSize + k);
                            }
                        }
                        out[idx] = translated.toArray(new String[0]);
                        priorSrc = tail(batch, overlap);
                        priorTgt = tail(translated, overlap);
                        if (onProgress != null) {
                            onProgress.accept(done.incrementAndGet());
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    log.warn("翻译分组异常（该组已内部降级）: {}", e.getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        List<String> result = new ArrayList<>(sources.size());
        for (int i = 0; i < batches.size(); i++) {
            if (out[i] == null) {
                result.addAll(batches.get(i));      // 兜底：该批完全没跑成
                for (int k = 0; k < batches.get(i).size(); k++) {
                    missing.add(i * batchSize + k);
                }
            } else {
                Collections.addAll(result, out[i]);
            }
        }
        List<Integer> miss = new ArrayList<>(missing);
        Collections.sort(miss);
        log.info("RAG 翻译完成：{} 行 / {} 批 / {} 组（组内串行带上下文，组间 {} 路并发），"
                        + "注入术语 {} 条，耗时 {}s{}",
                sources.size(), batches.size(), groups.size(), concurrency,
                glossary.size(), (System.currentTimeMillis() - t0) / 1000,
                miss.isEmpty() ? "" : "，" + miss.size() + " 行未译（保留原文）");
        return new RagResult(result, miss);
    }

    /** 取列表末尾 n 项作上下文。 */
    private static List<String> tail(List<String> list, int n) {
        if (list == null || list.isEmpty() || n <= 0) {
            return null;
        }
        int from = Math.max(0, list.size() - n);
        return new ArrayList<>(list.subList(from, list.size()));
    }
}
