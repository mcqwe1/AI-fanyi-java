package com.aifanyi.agent.node;

import com.aifanyi.agent.model.ScoredTerm;
import com.aifanyi.agent.model.TermBundle;
import com.aifanyi.agent.model.TermState;
import com.aifanyi.entity.GlossaryTerm;
import com.aifanyi.mapper.GlossaryTermMapper;
import com.aifanyi.mapper.KbProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ⑦ 跨任务确认飞轮：单任务算分被天花板压死在 0.82（防模型自吹），
 * 「已确认」的唯一非权威通道是同词同译跨任务反复出现——这组测试锁死该通道的三条规则：
 * 每次独立确认 +0.06、同一任务重试不刷分、用户手动/已确认永不覆盖。
 */
class TermStateMachineFlywheelTest {

    private final List<GlossaryTerm> existing = new ArrayList<>();
    private final List<GlossaryTerm> updates = new ArrayList<>();
    private final List<GlossaryTerm> inserts = new ArrayList<>();
    private TermStateMachine machine;

    @BeforeEach
    void setUp() {
        existing.clear();
        updates.clear();
        inserts.clear();
        GlossaryTermMapper termMapper = fake(GlossaryTermMapper.class);
        KbProjectMapper projectMapper = fake(KbProjectMapper.class);
        machine = new TermStateMachine(termMapper, projectMapper);
    }

    /** 只实现 persist 用到的三个方法，其余调用直接炸出来（说明被测代码行为变了）。 */
    @SuppressWarnings("unchecked")
    private <T> T fake(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectList" -> new ArrayList<>(existing);
                    case "updateById" -> {
                        updates.add((GlossaryTerm) args[0]);
                        yield 1;
                    }
                    case "insert" -> {
                        inserts.add((GlossaryTerm) args[0]);
                        yield 1;
                    }
                    case "toString" -> type.getSimpleName() + "Fake";
                    case "hashCode" -> 0;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static GlossaryTerm oldTerm(String source, String target, double conf,
                                        String status, String origin, Long lastTaskId) {
        GlossaryTerm g = new GlossaryTerm();
        g.setId(1L);
        g.setProjectId(9L);
        g.setSourceTerm(source);
        g.setTargetTerm(target);
        g.setSourceNorm(source.toLowerCase());
        g.setConfidence(BigDecimal.valueOf(conf));
        g.setStatus(status);
        g.setOrigin(origin);
        g.setEnabled(1);
        g.setLastTaskId(lastTaskId);
        return g;
    }

    private static ScoredTerm proposed(String source, String target, double conf) {
        return ScoredTerm.builder()
                .source(source).sourceNorm(source.toLowerCase()).target(target)
                .proposals(2).agreements(2).profileCode("it")
                .build().withConfidence(conf);
    }

    @Test
    void 另一任务同译确认_加分但仍在待确认档() {
        existing.add(oldTerm("ejection fraction", "射血分数", 0.767, "PENDING", "agent", 100L));
        TermBundle b = machine.persist(List.of(proposed("ejection fraction", "射血分数", 0.767)), 9L, 200L);

        assertEquals(1, updates.size());
        GlossaryTerm g = updates.get(0);
        assertEquals(0.827, g.getConfidence().doubleValue(), 1e-9);
        assertEquals(TermState.PENDING.name(), g.getStatus());
        assertEquals(200L, g.getLastTaskId());
        assertTrue(g.getNote().contains("跨任务译法一致"));
        assertEquals(1, b.persisted());
    }

    @Test
    void 第二次独立确认_越过阈值自动转正() {
        existing.add(oldTerm("ejection fraction", "射血分数", 0.827, "PENDING", "agent", 200L));
        machine.persist(List.of(proposed("ejection fraction", "射血分数", 0.767)), 9L, 300L);

        assertEquals(1, updates.size());
        // 0.827 + 0.06 = 0.887，被 0.88 封顶压住——但已越过 0.85 的自动确认线
        assertEquals(0.88, updates.get(0).getConfidence().doubleValue(), 1e-9);
        assertEquals(TermState.VERIFIED.name(), updates.get(0).getStatus());
    }

    @Test
    void 同一任务重跑不算确认_防重试刷分() {
        existing.add(oldTerm("ejection fraction", "射血分数", 0.827, "PENDING", "agent", 200L));
        machine.persist(List.of(proposed("ejection fraction", "射血分数", 0.767)), 9L, 200L);

        assertTrue(updates.isEmpty());          // 一次写库都没有
    }

    @Test
    void 已确认与手动条目永不覆盖() {
        existing.add(oldTerm("API", "接口", 0.9, "VERIFIED", "agent", 100L));
        existing.add(oldTerm("K8s", "K8s", 1.0, "PENDING", "manual", null));
        machine.persist(List.of(
                proposed("API", "接口", 0.8),
                proposed("K8s", "库伯内提斯", 0.8)), 9L, 200L);

        assertTrue(updates.isEmpty());
    }

    @Test
    void 译法不同时维持旧规则_只认更高分() {
        existing.add(oldTerm("cache", "缓存", 0.75, "PENDING", "agent", 100L));
        machine.persist(List.of(proposed("cache", "快取", 0.7)), 9L, 200L);
        assertTrue(updates.isEmpty());          // 分不够高，不换译法

        machine.persist(List.of(proposed("cache", "快取", 0.8)), 9L, 300L);
        assertEquals(1, updates.size());        // 分更高才换
        assertEquals("快取", updates.get(0).getTargetTerm());
        assertEquals(300L, updates.get(0).getLastTaskId());
    }

    @Test
    void 新词首插带上任务号() {
        TermBundle b = machine.persist(List.of(proposed("GraphQL", "GraphQL", 0.75)), 9L, 200L);
        assertEquals(1, inserts.size());
        assertEquals(200L, inserts.get(0).getLastTaskId());
        assertEquals(1, b.persisted());
    }
}
