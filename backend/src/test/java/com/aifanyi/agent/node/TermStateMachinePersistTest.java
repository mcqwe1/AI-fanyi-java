package com.aifanyi.agent.node;

import com.aifanyi.agent.model.ScoredTerm;
import com.aifanyi.agent.model.Strategy;
import com.aifanyi.agent.model.TermBundle;
import com.aifanyi.entity.GlossaryTerm;
import com.aifanyi.mapper.GlossaryTermMapper;
import com.aifanyi.mapper.KbProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ⑦ 落库行为（2026-08 置信度机制重构后）：
 * 分诊说启用就 enabled=1，说备选就 enabled=0 照样入库，
 * 库里已有同词同译的备选 → 转正；已有条目一律不覆盖。
 */
class TermStateMachinePersistTest {

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

    /** 只实现 persist 用到的方法，其余调用直接炸出来（说明被测代码行为变了）。 */
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

    private static GlossaryTerm oldTerm(String source, String target, String status,
                                        String origin, int enabled) {
        GlossaryTerm g = new GlossaryTerm();
        g.setId(1L);
        g.setProjectId(9L);
        g.setSourceTerm(source);
        g.setTargetTerm(target);
        g.setSourceNorm(Arbitrator.norm(source));
        g.setStatus(status);
        g.setOrigin(origin);
        g.setEnabled(enabled);
        return g;
    }

    /** 单方判断的自拟译法：术语性够（音译），但没佐证 → 备选。 */
    private static ScoredTerm singleCoined(String source, String target) {
        return ScoredTerm.builder()
                .source(source).sourceNorm(Arbitrator.norm(source)).target(target)
                .strategy(Strategy.TRANSLITERATE).reason("测试用理由")
                .proposals(1).agreements(1).profileCode("it").build();
    }

    /** 多专家一致的自拟译法 → 直接启用。 */
    private static ScoredTerm agreedCoined(String source, String target) {
        return ScoredTerm.builder()
                .source(source).sourceNorm(Arbitrator.norm(source)).target(target)
                .strategy(Strategy.TRANSLITERATE).reason("测试用理由")
                .proposals(2).agreements(2).profileCode("it").build();
    }

    @Test
    void 多方一致的词入库并直接启用() {
        TermBundle b = machine.persist(List.of(agreedCoined("Sawubona", "萨乌博纳")), 9L, 200L);
        assertEquals(1, inserts.size());
        GlossaryTerm g = inserts.get(0);
        assertEquals("ACTIVE", g.getStatus());
        assertEquals(1, g.getEnabled());
        assertEquals("auto", g.getOrigin(), "Agent 产出的来源必须是「自动」");
        assertEquals(200L, g.getLastTaskId());
        assertEquals(1, b.persisted());
    }

    /** 这是本次改造的核心：旧版此处落库 0 条，词当场蒸发。 */
    @Test
    void 单方判断的词入库为备选而不是被扔掉() {
        TermBundle b = machine.persist(List.of(singleCoined("Sawubona", "萨乌博纳")), 9L, 200L);
        assertEquals(1, inserts.size(), "旧版这里是 0 —— 用户永远看不到 Agent 抽出过什么");
        GlossaryTerm g = inserts.get(0);
        assertEquals("CANDIDATE", g.getStatus());
        assertEquals(0, g.getEnabled(), "备选词入库但不生效，等用户点头");
        assertEquals(1, b.persisted());
        assertEquals("萨乌博纳", b.map().get("Sawubona"), "备选词仍参与本次翻译，保证本片内一致");
    }

    @Test
    void 说明栏写人话而不是分数() {
        machine.persist(List.of(singleCoined("Sawubona", "萨乌博纳")), 9L, 200L);
        String note = inserts.get(0).getNote();
        assertTrue(note.startsWith("测试用理由"), "先说这个词是什么：" + note);
        assertTrue(note.contains("音译"), "再说为什么这样处置：" + note);
        assertFalse(note.matches(".*0\\.\\d+.*"), "不该出现裸分数：" + note);
    }

    // ─────────────── 跨任务晋升 ───────────────

    @Test
    void 库里已有同词同译的备选_本次再次抽到即转正() {
        existing.add(oldTerm("Sawubona", "萨乌博纳", "CANDIDATE", "auto", 0));
        TermBundle b = machine.persist(List.of(singleCoined("Sawubona", "萨乌博纳")), 9L, 300L);

        assertTrue(inserts.isEmpty(), "已有条目不重复插入");
        assertEquals(1, updates.size(), "应就地转正");
        GlossaryTerm g = updates.get(0);
        assertEquals(1, g.getEnabled(), "两个不同任务独立得出同一译法 = 真正的重复实验");
        assertEquals("ACTIVE", g.getStatus());
        assertEquals(300L, g.getLastTaskId());
        assertEquals(1, b.persisted());
    }

    @Test
    void 库里备选但译法不同_不转正也不覆盖() {
        existing.add(oldTerm("Sawubona", "萨武博纳", "CANDIDATE", "auto", 0));
        machine.persist(List.of(singleCoined("Sawubona", "萨乌博纳")), 9L, 300L);
        assertTrue(updates.isEmpty(), "译法对不上就不是佐证");
        assertTrue(inserts.isEmpty());
    }

    @Test
    void 已启用条目一律不覆盖_不论来源() {
        existing.add(oldTerm("API", "接口", "ACTIVE", "auto", 1));
        existing.add(oldTerm("K8s", "K8s", "ACTIVE", "manual", 1));
        machine.persist(List.of(
                agreedCoined("API", "应用程序接口"),
                agreedCoined("K8s", "库伯内提斯")), 9L, 200L);
        assertTrue(updates.isEmpty(), "术语一致性优先：库里已有的词不换译法");
        assertTrue(inserts.isEmpty());
    }

    // ─────────────── 注入映射 ───────────────

    @Test
    void 只有已启用的历史条目参与注入() {
        existing.add(oldTerm("API", "接口", "ACTIVE", "auto", 1));
        existing.add(oldTerm("cache", "高速缓存", "CANDIDATE", "auto", 0));
        TermBundle b = machine.persist(List.of(agreedCoined("GraphQL", "GraphQL")), 9L, 200L);
        assertEquals("接口", b.map().get("API"));
        assertFalse(b.map().containsKey("cache"), "没经用户点头的备选词不该跨任务悄悄生效");
        assertEquals("GraphQL", b.map().get("GraphQL"));
    }

    @Test
    void 逐词去向明细可读() {
        TermBundle b = machine.persist(List.of(singleCoined("Sawubona", "萨乌博纳")), 9L, 200L);
        assertEquals(1, b.ledger().size());
        assertTrue(b.ledger().get(0).contains("入库备选"), b.ledger().get(0));
        assertTrue(b.ledger().get(0).contains("Sawubona"), b.ledger().get(0));
    }
}
