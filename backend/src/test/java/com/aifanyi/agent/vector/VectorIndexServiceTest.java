package com.aifanyi.agent.vector;

import com.aifanyi.agent.model.ScoredTerm;
import com.aifanyi.entity.GlossaryTerm;
import com.aifanyi.mapper.GlossaryTermMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * VectorIndexService 单测。
 *
 * <p>重点<b>不是</b>验证相似度算得准（那取决于模型，已在 /embed 实测过），
 * 而是验证<b>它坏掉的时候不会拖垮主链</b>——向量只是「发现近义术语」的提示功能，
 * 架构图明令它不删任何数据；ai-service 没装的用户占多数，那条路径必须完好。
 */
class VectorIndexServiceTest {

    private static ScoredTerm term(String source, String target) {
        return ScoredTerm.builder()
                .source(source).sourceNorm(source.toLowerCase()).target(target)
                .selfReport("high").occurrences(1).proposals(1).agreements(1)
                .profileCode("it").build();
    }

    private static GlossaryTerm row(long id, String source, String target) {
        GlossaryTerm g = new GlossaryTerm();
        g.setId(id);
        g.setSourceTerm(source);
        g.setTargetTerm(target);
        g.setEnabled(1);
        return g;
    }

    /** 造一个 384 维单位向量：只有第 axis 维为 1，便于精确构造相似/不相似。 */
    private static float[] unit(int axis) {
        float[] v = new float[EmbeddingClient.DIM];
        v[axis] = 1f;
        return v;
    }

    /** 两个轴的等权混合，与 unit(a) 的余弦恰为 1/√2 ≈ 0.707（低于 0.75 阈值）。 */
    private static float[] mix(int a, int b) {
        float[] v = new float[EmbeddingClient.DIM];
        float w = (float) (1 / Math.sqrt(2));
        v[a] = w;
        v[b] = w;
        return v;
    }

    // ─────────────── 降级路径（最重要）───────────────

    /** <b>核心契约</b>：ai-service 不可用时原样返回，绝不抛异常、绝不丢词。 */
    @Test
    void returnsInputUnchangedWhenEmbeddingUnavailable() {
        EmbeddingClient embed = mock(EmbeddingClient.class);
        when(embed.embed(any())).thenReturn(List.of());          // 服务不可用的契约返回
        GlossaryTermMapper mapper = mock(GlossaryTermMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(row(1, "Kubernetes", "K8s")));

        VectorIndexService svc = new VectorIndexService(embed, mapper);
        List<ScoredTerm> in = List.of(term("Docker", "Docker"));
        List<ScoredTerm> out = svc.annotateRelated(in, 100L);

        assertEquals(in, out, "向量不可用时必须原样返回，不能丢词");
    }

    /** embedding 抛异常也不能传播出去。 */
    @Test
    void swallowsEmbeddingExceptions() {
        EmbeddingClient embed = mock(EmbeddingClient.class);
        when(embed.embed(any())).thenThrow(new RuntimeException("模拟 ai-service 炸裂"));
        GlossaryTermMapper mapper = mock(GlossaryTermMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(row(1, "Kubernetes", "K8s")));

        VectorIndexService svc = new VectorIndexService(embed, mapper);
        List<ScoredTerm> in = List.of(term("Docker", "Docker"));
        assertDoesNotThrow(() -> {
            assertEquals(in, svc.annotateRelated(in, 100L));
        });
    }

    /** 空库、空输入、null projectId 都安全。 */
    @Test
    void handlesEmptyInputsSafely() {
        EmbeddingClient embed = mock(EmbeddingClient.class);
        GlossaryTermMapper mapper = mock(GlossaryTermMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        VectorIndexService svc = new VectorIndexService(embed, mapper);

        assertTrue(svc.annotateRelated(List.of(), 1L).isEmpty());
        assertNull(svc.annotateRelated(null, 1L));
        List<ScoredTerm> one = List.of(term("A", "甲"));
        assertEquals(one, svc.annotateRelated(one, null), "无术语桶时原样返回");
        assertEquals(one, svc.annotateRelated(one, 1L), "空库时原样返回");
        verifyNoInteractions(embed);
    }

    /** 返回条数与入参对不齐时整体放弃——错位的向量比没有向量危险得多。 */
    @Test
    void abortsOnMisalignedVectorCount() {
        EmbeddingClient embed = mock(EmbeddingClient.class);
        GlossaryTermMapper mapper = mock(GlossaryTermMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(row(1, "A", "甲"), row(2, "B", "乙")));
        // 建索引时给足 2 条，标注时只给 1 条（模型漏返回）
        when(embed.embed(any()))
                .thenReturn(List.of(unit(0), unit(1)))
                .thenReturn(List.of(unit(0)));

        VectorIndexService svc = new VectorIndexService(embed, mapper);
        List<ScoredTerm> in = List.of(term("X", "丙"), term("Y", "丁"));
        assertEquals(in, svc.annotateRelated(in, 5L), "条数对不齐必须整体放弃");
    }

    // ─────────────── 正常路径 ───────────────

    /** 相似度过阈值的条目被标注为疑似同词族，低于阈值的不标注。 */
    @Test
    void annotatesOnlyAboveThreshold() {
        EmbeddingClient embed = mock(EmbeddingClient.class);
        GlossaryTermMapper mapper = mock(GlossaryTermMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                row(11, "アルツハイマー病", "阿尔茨海默病"),
                row(22, "apple pie", "苹果派")));
        when(embed.embed(any()))
                .thenReturn(List.of(unit(0), unit(5)))         // 建索引：两条互不相似
                .thenReturn(List.of(unit(0), mix(5, 9)));      // 标注：第1条与 id=11 完全同向；第2条仅 0.707

        VectorIndexService svc = new VectorIndexService(embed, mapper);
        List<ScoredTerm> out = svc.annotateRelated(
                List.of(term("阿尔茨海默症", "阿尔茨海默病"), term("banana", "香蕉")), 7L);

        assertEquals(List.of(11L), out.get(0).relatedIds(), "余弦=1.0 应被标注");
        assertTrue(out.get(1).relatedIds().isEmpty(), "余弦 0.707 低于 0.75 阈值，不该标注");
    }

    /** 不能把自己报成自己的近义词（同名条目已在库里时）。 */
    @Test
    void doesNotMatchItself() {
        EmbeddingClient embed = mock(EmbeddingClient.class);
        GlossaryTermMapper mapper = mock(GlossaryTermMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(row(11, "Kubernetes", "K8s")));
        when(embed.embed(any()))
                .thenReturn(List.of(unit(0)))
                .thenReturn(List.of(unit(0)));                 // 完全同向

        VectorIndexService svc = new VectorIndexService(embed, mapper);
        List<ScoredTerm> out = svc.annotateRelated(List.of(term("Kubernetes", "K8s")), 9L);
        assertTrue(out.get(0).relatedIds().isEmpty(), "同名条目不该被报成自己的近义词");
    }

    /** 索引按桶隔离：A 桶的术语不该召回 B 桶的条目。 */
    @Test
    void bucketsAreIsolated() {
        EmbeddingClient embed = mock(EmbeddingClient.class);
        GlossaryTermMapper mapper = mock(GlossaryTermMapper.class);
        List<GlossaryTerm> bucketA = List.of(row(11, "A词", "甲"));
        List<GlossaryTerm> bucketB = List.of(row(99, "B词", "乙"));
        when(mapper.selectList(any())).thenReturn(bucketA, bucketB);
        when(embed.embed(any())).thenReturn(List.of(unit(0)));  // 一律同向，只看桶隔离

        VectorIndexService svc = new VectorIndexService(embed, mapper);
        assertEquals(List.of(11L), svc.annotateRelated(List.of(term("X", "丙")), 1L).get(0).relatedIds());
        assertEquals(List.of(99L), svc.annotateRelated(List.of(term("X", "丙")), 2L).get(0).relatedIds());
    }

    /** 索引懒加载后应被缓存，不该每次任务都重新向量化整个库。 */
    @Test
    void cachesBucketIndex() {
        EmbeddingClient embed = mock(EmbeddingClient.class);
        GlossaryTermMapper mapper = mock(GlossaryTermMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(row(11, "A", "甲")));
        when(embed.embed(any())).thenReturn(List.of(unit(0)));

        VectorIndexService svc = new VectorIndexService(embed, mapper);
        svc.annotateRelated(List.of(term("X", "丙")), 3L);
        svc.annotateRelated(List.of(term("Y", "丁")), 3L);

        verify(mapper, times(1)).selectList(any());
        // 建索引 1 次 + 两次标注各 1 次 = 3 次；若无缓存则会是 4 次
        verify(embed, times(3)).embed(any());
    }

    /** invalidate 后重建，让用户在术语库页的改动能生效。 */
    @Test
    void invalidateForcesRebuild() {
        EmbeddingClient embed = mock(EmbeddingClient.class);
        GlossaryTermMapper mapper = mock(GlossaryTermMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(row(11, "A", "甲")));
        when(embed.embed(any())).thenReturn(List.of(unit(0)));

        VectorIndexService svc = new VectorIndexService(embed, mapper);
        svc.annotateRelated(List.of(term("X", "丙")), 4L);
        svc.invalidate(4L);
        svc.annotateRelated(List.of(term("X", "丙")), 4L);

        verify(mapper, times(2)).selectList(any());
    }

    /** 观测接口不该抛异常。 */
    @Test
    void statsIsSafe() {
        VectorIndexService svc = new VectorIndexService(
                mock(EmbeddingClient.class), mock(GlossaryTermMapper.class));
        assertEquals(EmbeddingClient.DIM, svc.stats().get("dim"));
        assertEquals(0, svc.stats().get("buckets"));
    }

    /** 索引条数上限：极端库不该把内存撑爆。 */
    @Test
    void respectsPerBucketCap() {
        EmbeddingClient embed = mock(EmbeddingClient.class);
        GlossaryTermMapper mapper = mock(GlossaryTermMapper.class);
        List<GlossaryTerm> many = new ArrayList<>();
        List<float[]> vecs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            many.add(row(i + 1, "词" + i, "t" + i));
            vecs.add(unit(i % EmbeddingClient.DIM));
        }
        when(mapper.selectList(any())).thenReturn(many);
        when(embed.embed(any())).thenReturn(vecs).thenReturn(List.of(unit(0)));

        VectorIndexService svc = new VectorIndexService(embed, mapper);
        svc.annotateRelated(List.of(term("X", "丙")), 8L);
        assertEquals(50, svc.stats().get("vectors"));
    }
}
