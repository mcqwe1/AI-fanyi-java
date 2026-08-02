package com.aifanyi.agent.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本次任务可用的术语集合：历史库条目 + 本次新产出（含不落库的 EPHEMERAL）。
 * <p>⑧ RAG 翻译从这里取注入 prompt 的术语映射。
 *
 * @param map      原文 → 译法（长词优先，避免子串误命中；与 KB 模式的 loadGlossary 同约定）
 * @param fresh    本次新产出的条目（前端运行详情面板展示用）
 * @param persisted 本次实际落库的条数
 * @param indexable 本次新落库的实体（供事务提交后补建向量索引；<b>不可在事务内向量化</b>——
 *                  那是一次可能几十秒的 HTTP，会把 H2 的写锁按住同样久）
 */
public record TermBundle(Map<String, String> map, List<ScoredTerm> fresh, int persisted,
                         List<com.aifanyi.entity.GlossaryTerm> indexable) {

    public static TermBundle empty() {
        return new TermBundle(Map.of(), List.of(), 0, List.of());
    }

    /** 合并历史库映射与本次产出，长词优先排序。 */
    public static TermBundle of(Map<String, String> history, List<ScoredTerm> fresh, int persisted) {
        return of(history, fresh, persisted, List.of());
    }

    public static TermBundle of(Map<String, String> history, List<ScoredTerm> fresh, int persisted,
                                List<com.aifanyi.entity.GlossaryTerm> indexable) {
        Map<String, String> all = new LinkedHashMap<>();
        if (history != null) {
            all.putAll(history);
        }
        for (ScoredTerm t : fresh) {
            all.put(t.source(), t.target());       // 本次产出覆盖历史（已过冲突优先级检查）
        }
        // 长词优先：OpenAiTranslator 按插入序遍历，长词在前可避免子串误命中
        Map<String, String> sorted = new LinkedHashMap<>();
        all.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return new TermBundle(sorted, fresh, persisted,
                indexable == null ? List.of() : indexable);
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public int size() {
        return map.size();
    }
}
