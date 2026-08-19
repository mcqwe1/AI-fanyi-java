package com.aifanyi.agent.node;

import com.aifanyi.agent.model.ScoredTerm;
import com.aifanyi.agent.model.SubAgentResult;
import com.aifanyi.agent.model.TermDraft;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.*;

/**
 * ⑤ 主 Agent · 汇总仲裁：去重 + 冲突处理。
 *
 * <p>2026-08 重构后本类<b>不再算分</b>——「存不存库、启不启用」由 {@link TermTriage} 按规则判定。
 * 这里只负责把多个专家的候选合并成一张确定性的唯一术语表。
 *
 * <p><b>本类是全流程唯一的删除点</b>——其余节点只增不删，这样「术语为什么没了」
 * 永远只需要查一个地方。
 *
 * <p>去重靠规范化后精确匹配（API/api 在此合并）。架构图说「命中即删」但没说留哪条，
 * 那会导致同一批输入每次跑出不同术语表；这里给出<b>确定性裁决链</b>：
 * <pre>
 *   分高者 → 有权威证据者 → 原始形态更长者（保 API 而非 api）→ 档案序号小者
 * </pre>
 *
 * <p>冲突（同源词不同译且票数接近）<b>不花一次 LLM 仲裁</b>——标记后交 {@link TermTriage}，
 * 由它统一处置为「入库备选」，用户在术语库 UI 里一键裁决。
 * 一个已经花掉 2N+1 次调用的功能，不该为此再加一次。
 */
@Slf4j
@Component
public class Arbitrator {

    /**
     * 仲裁全部子 Agent 的产出。
     *
     * @param results   各子 Agent 结果（含部分结果）
     * @param profileOrder 档案顺序（裁决链最后一级用）
     */
    public List<ScoredTerm> arbitrate(List<SubAgentResult> results, List<String> profileOrder) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        // 按规范化形式分组：这是唯一的合并依据
        Map<String, List<Cand>> groups = new LinkedHashMap<>();
        for (SubAgentResult r : results) {
            for (TermDraft d : r.terms()) {
                if (!d.valid()) {
                    continue;
                }
                String norm = norm(d.source());
                if (norm.isEmpty()) {
                    continue;
                }
                groups.computeIfAbsent(norm, k -> new ArrayList<>())
                        .add(new Cand(d, r.degraded()));
            }
        }

        List<ScoredTerm> out = new ArrayList<>(groups.size());
        int merged = 0;
        int conflicted = 0;
        for (Map.Entry<String, List<Cand>> e : groups.entrySet()) {
            List<Cand> cands = e.getValue();
            if (cands.size() > 1) {
                merged += cands.size() - 1;
            }
            ScoredTerm t = resolveGroup(e.getKey(), cands, profileOrder);
            if (t.hasConflict()) {
                conflicted++;
            }
            out.add(t);
        }
        // 证据强度降序：权威 → 多方一致 → 高频 → 原文字典序（最后一级保证同一输入永远同一顺序）。
        // 旧版按置信度分数排，分数删掉后改用它的构成要素直接排，展示与截断的效果一致。
        out.sort((a, b) -> {
            int byAuthority = Boolean.compare(b.hasAuthority(), a.hasAuthority());
            if (byAuthority != 0) {
                return byAuthority;
            }
            int byAgree = Integer.compare(b.agreements(), a.agreements());
            if (byAgree != 0) {
                return byAgree;
            }
            int byOcc = Integer.compare(b.occurrences(), a.occurrences());
            return byOcc != 0 ? byOcc : a.source().compareTo(b.source());
        });
        log.info("仲裁完成：{} 条候选 → {} 条唯一术语（合并 {} 条重复，{} 条存在译法冲突）",
                results.stream().mapToInt(r -> r.terms().size()).sum(), out.size(), merged, conflicted);
        return out;
    }

    /** 组内择优 + 统计一致性 + 算分。 */
    private ScoredTerm resolveGroup(String norm, List<Cand> cands, List<String> profileOrder) {
        // 按译法分桶，统计各译法得票
        Map<String, List<Cand>> byTarget = new LinkedHashMap<>();
        for (Cand c : cands) {
            byTarget.computeIfAbsent(normTarget(c.d.target()), k -> new ArrayList<>()).add(c);
        }
        // 胜出译法：先看票数，票数相同走裁决链
        List<Map.Entry<String, List<Cand>>> buckets = new ArrayList<>(byTarget.entrySet());
        buckets.sort((x, y) -> {
            int byVotes = Integer.compare(y.getValue().size(), x.getValue().size());
            if (byVotes != 0) {
                return byVotes;
            }
            return compareCands(x.getValue().get(0), y.getValue().get(0), profileOrder);
        });

        List<Cand> winners = buckets.get(0).getValue();
        // 组内代表：同译法的多个候选里也走裁决链，保证确定性
        Cand best = winners.stream()
                .min((a, b) -> compareCands(a, b, profileOrder))
                .orElse(winners.get(0));

        // 冲突：存在其他译法，且票数接近（差距 ≤1 票）→ 标记交用户
        List<String> conflicts = new ArrayList<>();
        for (int i = 1; i < buckets.size(); i++) {
            List<Cand> other = buckets.get(i).getValue();
            if (winners.size() - other.size() <= 1) {
                conflicts.add(other.get(0).d.target());
            }
        }

        boolean allDegraded = cands.stream().allMatch(c -> c.degraded);
        return ScoredTerm.builder()
                .source(best.d.source())
                .sourceNorm(norm)
                .target(best.d.target())
                .strategy(best.d.strategy())
                .evidence(best.d.evidence())
                .authorityUrl(best.d.authorityUrl())
                .reason(best.d.reason())
                .selfReport(best.d.selfReport())
                .occurrences(cands.stream().mapToInt(c -> c.d.occurrences()).max().orElse(0))
                .proposals(cands.size())
                .agreements(winners.size())
                .conflicts(conflicts)
                .profileCode(best.d.profileCode())
                .degraded(allDegraded)
                .build();
    }

    /**
     * 确定性裁决链：有权威证据者 → 原始形态更长者 → 档案序号小者 → 原文字典序。
     * 最后一级保证即使前面全平也有稳定结果（同一输入永远给同一术语表）。
     */
    private int compareCands(Cand a, Cand b, List<String> profileOrder) {
        int byAuthority = Boolean.compare(b.d.hasAuthority(), a.d.hasAuthority());
        if (byAuthority != 0) {
            return byAuthority;
        }
        // 保留信息量更大的原始形态：API 胜过 api
        int byLen = Integer.compare(b.d.source().length(), a.d.source().length());
        if (byLen != 0) {
            return byLen;
        }
        int byCase = Integer.compare(countUpper(b.d.source()), countUpper(a.d.source()));
        if (byCase != 0) {
            return byCase;
        }
        int ia = indexOf(profileOrder, a.d.profileCode());
        int ib = indexOf(profileOrder, b.d.profileCode());
        if (ia != ib) {
            return Integer.compare(ia, ib);
        }
        return a.d.source().compareTo(b.d.source());
    }

    /**
     * 规范化：小写 + NFKC 全半角统一 + 去空白 + 去标点。
     * <p>注意 sourceNorm <b>只作查重键，绝不用于展示</b>——去空白会把 "a i" 并成 "ai"，
     * 小写会把德语 SIE/sie 并成一个，作为键可接受，作为显示值则是错的。
     * 展示一律用胜出候选的原始 source。
     */
    public static String norm(String s) {
        if (s == null) {
            return "";
        }
        String t = Normalizer.normalize(s.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
            // 空白与标点一律丢弃
        }
        return sb.toString();
    }

    /** 译法比较用的宽松归一（只做 trim + 小写，保留内部空格）。 */
    private static String normTarget(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static int countUpper(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isUpperCase(s.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    private static int indexOf(List<String> order, String code) {
        if (order == null || code == null) {
            return Integer.MAX_VALUE;
        }
        int i = order.indexOf(code);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private record Cand(TermDraft d, boolean degraded) {
    }
}
