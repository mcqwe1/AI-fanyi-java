package com.aifanyi.agent.vector;

import com.aifanyi.agent.model.ScoredTerm;
import com.aifanyi.entity.GlossaryTerm;
import com.aifanyi.mapper.GlossaryTermMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ⑤ 术语近义发现（JVM 内存向量索引）。
 *
 * <p><b>为什么不用 Qdrant（推翻原方案）</b>：实测这个规模下暴力余弦比走 Qdrant 更快。
 * 384 维、向量已 L2 归一化（余弦=点积），JIT 预热后单次 top-K 检索：
 * <pre>
 *   200 条   0.153 ms      1,000 条 0.660 ms
 *   5,000 条 2.822 ms      20,000 条 13.2 ms
 * </pre>
 * 而 localhost 的一次 HTTP 往返本身就要 1~3ms。术语按「用户 × 领域」分桶、
 * 召回只在单桶内做，真实规模是几百条——ANN 索引的价值在百万级避免全扫，
 * 几百条上它纯属负优化，还要付出 30MB 二进制 + 一个需要拉起/健康检查/退出时清理的
 * 孤儿进程（项目已有因残留进程占用文件导致打包失败的教训）。
 *
 * <p><b>定位是提示，不是依赖</b>：架构图写明「向量召回不删任何数据，仅发现已有近义条目
 * → 提示主 Agent 做词族合并关联」。主链的术语注入走精确字符串匹配，本类整个挂掉
 * 也只是少一条提示。故所有方法都不抛异常。
 *
 * <p>实测佐证这个定位是对的：跨语言近义能认（アルツハイマー病↔阿尔茨海默病 0.99），
 * 但<b>缩写认不出</b>（Kubernetes↔K8s 仅 0.34）——所以它当不了术语召回，只能当提示。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorIndexService {

    /** 判定「疑似同词族」的余弦阈值。实测：跨语言近义 0.79~0.99，无关词 0.20 上下。 */
    private static final float SIMILAR_THRESHOLD = 0.75f;
    /** 每条术语最多提示几个近义条目 */
    private static final int TOP_K = 3;
    /** 单桶索引条数上限：超过就不再加载，防止极端库把内存撑爆（20000×384×4B≈29MB） */
    private static final int MAX_PER_BUCKET = 20_000;

    private final EmbeddingClient embedding;
    private final GlossaryTermMapper termMapper;

    /** 桶（kb_project.id）→ 该桶的向量索引。懒加载，进程内缓存。 */
    private final Map<Long, Bucket> buckets = new ConcurrentHashMap<>();

    /** 一个术语桶的向量索引（不可变快照 + 追加）。 */
    private static final class Bucket {
        private final List<Long> ids = new ArrayList<>();
        private final List<String> sources = new ArrayList<>();
        private final List<float[]> vectors = new ArrayList<>();

        synchronized void add(Long id, String source, float[] vec) {
            if (ids.size() >= MAX_PER_BUCKET) {
                return;
            }
            ids.add(id);
            sources.add(source);
            vectors.add(vec);
        }

        synchronized int size() {
            return ids.size();
        }

        /** 暴力 top-K：向量已归一化，点积即余弦。 */
        synchronized List<Hit> search(float[] q, int k, float threshold, String excludeSource) {
            List<Hit> hits = new ArrayList<>();
            for (int i = 0; i < vectors.size(); i++) {
                if (excludeSource != null && excludeSource.equalsIgnoreCase(sources.get(i))) {
                    continue;                       // 别把自己报成自己的近义词
                }
                float s = dot(q, vectors.get(i));
                if (s >= threshold) {
                    hits.add(new Hit(ids.get(i), sources.get(i), s));
                }
            }
            hits.sort((a, b) -> Float.compare(b.score, a.score));
            return hits.size() > k ? new ArrayList<>(hits.subList(0, k)) : hits;
        }
    }

    /** 一条近义命中。 */
    public record Hit(Long termId, String sourceTerm, float score) {
    }

    private static float dot(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        float s = 0;
        for (int i = 0; i < n; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    /**
     * 给本次抽出的术语标注「库里疑似同词族的已有条目」。
     *
     * <p>只标注不合并——架构图明令这里<b>不删任何数据</b>，合并与否交用户在术语库页决定。
     *
     * @return 带 relatedIds 的术语列表；向量不可用时<b>原样返回</b>（降级为无提示）
     */
    public List<ScoredTerm> annotateRelated(List<ScoredTerm> scored, Long projectId) {
        if (scored == null || scored.isEmpty() || projectId == null) {
            return scored;
        }
        try {
            Bucket bucket = load(projectId);
            if (bucket == null || bucket.size() == 0) {
                return scored;                      // 空库没什么可关联的
            }
            List<String> queries = scored.stream().map(VectorIndexService::embedText).toList();
            List<float[]> vecs = embedding.embed(queries);
            if (vecs.size() != scored.size()) {
                return scored;                      // 向量不可用 → 无提示，不是错误
            }
            List<ScoredTerm> out = new ArrayList<>(scored.size());
            int annotated = 0;
            for (int i = 0; i < scored.size(); i++) {
                ScoredTerm t = scored.get(i);
                List<Hit> hits = bucket.search(vecs.get(i), TOP_K, SIMILAR_THRESHOLD, t.source());
                if (hits.isEmpty()) {
                    out.add(t);
                    continue;
                }
                out.add(t.withRelated(hits.stream().map(Hit::termId).toList()));
                annotated++;
                log.debug("术语「{}」疑似同词族: {}", t.source(),
                        hits.stream().map(h -> h.sourceTerm + "(" + String.format("%.2f", h.score) + ")")
                                .toList());
            }
            if (annotated > 0) {
                log.info("近义发现：{} 条术语找到疑似同词族条目（仅提示，不自动合并）", annotated);
            }
            return out;
        } catch (Exception e) {
            // 提示性功能，出任何问题都不该影响主链
            log.warn("近义发现失败，本次跳过（不影响翻译）: {}", e.toString());
            return scored;
        }
    }

    /**
     * 把新落库的术语补进索引（供下次任务的近义发现使用）。
     * <p>同步执行但很轻（几十条一次 HTTP）；失败只是这批词暂时没进索引，
     * 下次进程重启会由 {@link #load} 从 glossary_term 全量重建。
     */
    public void index(Long projectId, List<GlossaryTerm> terms) {
        if (projectId == null || terms == null || terms.isEmpty()) {
            return;
        }
        try {
            Bucket bucket = buckets.get(projectId);
            if (bucket == null) {
                return;             // 尚未加载过的桶，等首次 load 时全量建，避免半截索引
            }
            List<GlossaryTerm> valid = terms.stream()
                    .filter(g -> g.getId() != null && g.getSourceTerm() != null
                            && !g.getSourceTerm().isBlank())
                    .toList();
            if (valid.isEmpty()) {
                return;
            }
            List<float[]> vecs = embedding.embed(valid.stream()
                    .map(VectorIndexService::embedText).toList());
            if (vecs.size() != valid.size()) {
                return;
            }
            for (int i = 0; i < valid.size(); i++) {
                bucket.add(valid.get(i).getId(), valid.get(i).getSourceTerm(), vecs.get(i));
            }
            markVectorized(valid);
        } catch (Exception e) {
            log.debug("增量索引失败（下次重启会全量重建）: {}", e.toString());
        }
    }

    /** 标记已向量化：进程重启后据此知道哪些条目已进过索引（当前实现全量重建，留作观测）。 */
    private void markVectorized(List<GlossaryTerm> terms) {
        for (GlossaryTerm g : terms) {
            try {
                GlossaryTerm u = new GlossaryTerm();
                u.setId(g.getId());
                u.setVectorStatus(1);
                termMapper.updateById(u);
            } catch (Exception ignored) {
                // 标记失败无害：索引在内存里已经建好了
            }
        }
    }

    /** 懒加载某个桶的索引：从 glossary_term 取全部已启用条目，一次批量向量化。 */
    private Bucket load(Long projectId) {
        Bucket cached = buckets.get(projectId);
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = buckets.get(projectId);
            if (cached != null) {
                return cached;
            }
            List<GlossaryTerm> terms = termMapper.selectList(
                    Wrappers.<GlossaryTerm>lambdaQuery()
                            .eq(GlossaryTerm::getProjectId, projectId)
                            .eq(GlossaryTerm::getEnabled, 1)
                            .last("limit " + MAX_PER_BUCKET));
            Bucket b = new Bucket();
            if (terms.isEmpty()) {
                buckets.put(projectId, b);
                return b;
            }
            long t0 = System.currentTimeMillis();
            List<float[]> vecs = embedding.embed(terms.stream()
                    .map(VectorIndexService::embedText).toList());
            if (vecs.size() != terms.size()) {
                // 向量化不可用：缓存一个空桶，避免每次任务都重试一遍拖慢主链
                buckets.put(projectId, b);
                log.info("术语桶 #{} 向量化不可用，近义发现本次禁用（不影响翻译）", projectId);
                return b;
            }
            for (int i = 0; i < terms.size(); i++) {
                b.add(terms.get(i).getId(), terms.get(i).getSourceTerm(), vecs.get(i));
            }
            buckets.put(projectId, b);
            log.info("术语桶 #{} 向量索引就绪：{} 条，耗时 {}ms",
                    projectId, b.size(), System.currentTimeMillis() - t0);
            return b;
        }
    }

    /** 术语库被外部改动（用户编辑/删除）后丢弃缓存，下次用时重建。 */
    public void invalidate(Long projectId) {
        if (projectId != null) {
            buckets.remove(projectId);
        }
    }

    /**
     * 向量化的文本：<b>源词 + 译文一起</b>。
     * 术语表天然双语，只用源词会让「アルツハイマー病」与「阿尔茨海默病」这类
     * 跨语言同义对因分处两端而算不出高相似度——而这恰恰是本功能最该抓住的场景。
     */
    private static String embedText(ScoredTerm t) {
        return join(t.source(), t.target());
    }

    private static String embedText(GlossaryTerm g) {
        return join(g.getSourceTerm(), g.getTargetTerm());
    }

    private static String join(String source, String target) {
        if (target == null || target.isBlank()) {
            return source == null ? "" : source;
        }
        return source + " " + target;
    }

    /** 观测用：当前缓存了几个桶、共多少条向量。 */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("buckets", buckets.size());
        m.put("vectors", buckets.values().stream().mapToInt(Bucket::size).sum());
        m.put("dim", EmbeddingClient.DIM);
        return m;
    }
}
