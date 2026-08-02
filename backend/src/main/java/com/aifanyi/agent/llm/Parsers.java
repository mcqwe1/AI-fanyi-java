package com.aifanyi.agent.llm;

import com.aifanyi.agent.model.Strategy;
import com.aifanyi.agent.model.TermDraft;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把模型返回的 JSON 解析成领域对象。
 * <p>一律「能捞多少算多少」：单条字段缺失只丢那一条，绝不整批失败——
 * 这是 harness 降级哲学在解析层的体现（照 OpenAiTranslator 的 index 对齐回填思路）。
 */
public final class Parsers {

    private Parsers() {
    }

    /** 解析步骤 A：提取候选。 */
    public static List<TermDraft> parseExtract(JsonNode root, String profileCode) {
        List<TermDraft> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonNode terms = root.path("terms");
        if (!terms.isArray()) {
            return out;
        }
        for (JsonNode t : terms) {
            String source = text(t, "source");
            if (source.isBlank()) {
                continue;
            }
            String target = text(t, "target");
            boolean needSearch = t.path("needSearch").asBoolean(false);
            String conf = text(t, "confidence");
            List<String> queries = new ArrayList<>();
            JsonNode q = t.path("queries");
            if (q.isArray()) {
                for (JsonNode one : q) {
                    String s = one.asText("").trim();
                    if (!s.isBlank()) {
                        queries.add(s);
                    }
                }
            }
            out.add(TermDraft.of(source.trim(), target.trim(), needSearch, conf, queries, profileCode));
        }
        return out;
    }

    /**
     * 解析步骤 C 并合并回原候选列表（按 source 对齐）。
     * <p>返回的是<b>完整列表</b>：模型漏返回的条目保留步骤 A 的初步译法，
     * 不会因为模型少写一条就丢掉那个术语。
     */
    public static List<TermDraft> mergeResolve(List<TermDraft> drafts, JsonNode root) {
        if (root == null) {
            return drafts;
        }
        Map<String, JsonNode> bySource = new LinkedHashMap<>();
        JsonNode terms = root.path("terms");
        if (terms.isArray()) {
            for (JsonNode t : terms) {
                String s = text(t, "source").trim();
                if (!s.isBlank()) {
                    bySource.put(norm(s), t);
                }
            }
        }
        List<TermDraft> out = new ArrayList<>(drafts.size());
        for (TermDraft d : drafts) {
            JsonNode t = bySource.get(norm(d.source()));
            if (t == null) {
                out.add(d);                       // 模型漏了这条 → 保留初步结果
                continue;
            }
            out.add(d.resolved(
                    text(t, "target"),
                    Strategy.parse(text(t, "strategy")),
                    text(t, "evidence"),
                    // 模型没写链接就保持 null，别塞空串：⑤ 用 null/"" 区分「没搜过」与
                    // 「搜了没命中权威」，前者特征缺失参与重归一，后者按 0 分计入。
                    // 无脑写 "" 会让没配搜索的用户每个词都白丢一档分数。
                    // 真正的核验在 SubAgentTask.verifyAuthority（拿实际检索结果比对）。
                    emptyToNull(text(t, "authorityUrl")),
                    text(t, "reason"),
                    text(t, "confidence")));
        }
        return out;
    }

    /** 取出档案修正提议（④→⑥ 回路）；没有则返回 null。 */
    public static String extractProposal(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode p = root.path("profileProposal");
        if (p.isMissingNode() || p.isNull() || !p.isObject() || p.isEmpty()) {
            return null;
        }
        return p.toString();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText("");
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** 对齐用的宽松归一（大小写与首尾空白无关）。 */
    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
