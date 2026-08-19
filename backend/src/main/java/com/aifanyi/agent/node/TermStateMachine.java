package com.aifanyi.agent.node;

import com.aifanyi.agent.model.ScoredTerm;
import com.aifanyi.agent.model.TermBundle;
import com.aifanyi.agent.model.TermState;
import com.aifanyi.entity.GlossaryTerm;
import com.aifanyi.entity.KbProject;
import com.aifanyi.mapper.GlossaryTermMapper;
import com.aifanyi.mapper.KbProjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ⑦ 术语状态机 + 落库（harness 状态与记忆层）。
 *
 * <p><b>2026-08 重构</b>：去向不再由置信度阈值决定，改由 {@link TermTriage} 按规则判定。
 * 三个实质变化：
 * <ol>
 *   <li><b>「没把握」不再等于蒸发</b>：该固化但暂无佐证的词入库为 CANDIDATE（enabled=0），
 *       用户在术语库页面一眼过、一键启用。旧版把这批词直接丢掉，用户永远不知道
 *       Agent 抽出过什么——而它们恰恰是最需要人来拍板的。</li>
 *   <li><b>跨任务晋升</b>：库里已有同一个词、同一个译法的备选条目，说明<b>此前另一个任务
 *       独立得出过同样结论</b>。这是真正的独立重复实验（不同素材、不同上下文），
 *       比「同一次任务里换个 system prompt 跑两遍」硬得多，且零 token。命中即转正启用。</li>
 *   <li><b>已入库条目一律不覆盖</b>（保留旧规则）：术语的价值在于<b>一致</b>，
 *       反复横跳比某次「更对」损失更大。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TermStateMachine {

    private final GlossaryTermMapper termMapper;
    private final KbProjectMapper projectMapper;

    /**
     * 分诊 → 落库 → 产出本次可用的术语集合。
     *
     * @param scored    仲裁后的术语
     * @param projectId 术语桶（kb_project）id
     * @param taskId    本次任务 id（记录条目出处）
     * @return 本次翻译可用的 TermBundle（含历史库已启用条目 + 本次全部可用条目）
     */
    @Transactional
    public TermBundle persist(List<ScoredTerm> scored, Long projectId, Long taskId) {
        // 历史库：本项目已有条目（含用户手工录入的）
        List<GlossaryTerm> existing = termMapper.selectList(
                Wrappers.<GlossaryTerm>lambdaQuery().eq(GlossaryTerm::getProjectId, projectId));
        Map<String, GlossaryTerm> byNorm = new LinkedHashMap<>();
        Map<String, String> history = new LinkedHashMap<>();
        for (GlossaryTerm g : existing) {
            String key = g.getSourceNorm() != null ? g.getSourceNorm() : Arbitrator.norm(g.getSourceTerm());
            byNorm.put(key, g);
            // 只有已启用的历史条目参与注入——备选词还没经用户点头，不该悄悄生效
            if (g.getEnabled() != null && g.getEnabled() == 1
                    && g.getSourceTerm() != null && g.getTargetTerm() != null) {
                history.put(g.getSourceTerm(), g.getTargetTerm());
            }
        }

        List<ScoredTerm> usable = new ArrayList<>();
        List<GlossaryTerm> indexable = new ArrayList<>();
        List<String> ledger = new ArrayList<>();
        int activated = 0;
        int candidates = 0;
        int promoted = 0;
        int skippedExisting = 0;
        int ephemeral = 0;
        int discarded = 0;

        for (ScoredTerm t : scored) {
            GlossaryTerm prior = byNorm.get(t.sourceNorm());
            // 跨任务一致性信号：此前已有条目（自动产出或用户手工）给出了同一个译法
            boolean historyAgrees = prior != null && sameTarget(prior.getTargetTerm(), t.target());

            TermTriage.Verdict v = TermTriage.triage(t, historyAgrees);
            if (v.state() == TermState.DISCARD) {
                discarded++;
                ledger.add(row(t, v, "丢弃"));
                continue;
            }
            usable.add(t);                          // 备选与仅本次的词都参与本次注入，保证本片内一致
            if (!v.state().persistent()) {
                ephemeral++;
                ledger.add(row(t, v, "仅本次"));
                continue;
            }

            if (prior == null) {
                GlossaryTerm g = new GlossaryTerm();
                g.setProjectId(projectId);
                g.setOrigin("auto");
                g.setVectorStatus(0);
                g.setLastTaskId(taskId);
                apply(g, t, v);
                termMapper.insert(g);
                byNorm.put(t.sourceNorm(), g);
                indexable.add(g);                   // 事务外再向量化（见 TermBundle.indexable）
                if (v.state() == TermState.ACTIVE) {
                    activated++;
                    ledger.add(row(t, v, "入库启用"));
                } else {
                    candidates++;
                    ledger.add(row(t, v, "入库备选"));
                }
                continue;
            }

            // 已有条目：只允许「备选 → 启用」这一种变更，其余一律不动
            boolean priorIsCandidate = prior.getEnabled() != null && prior.getEnabled() == 0;
            if (priorIsCandidate && historyAgrees && v.state() == TermState.ACTIVE) {
                prior.setEnabled(1);
                prior.setStatus(TermState.ACTIVE.name());
                prior.setNote(truncate(buildNote(t, v), 500));
                prior.setLastTaskId(taskId);
                termMapper.updateById(prior);
                promoted++;
                ledger.add(row(t, v, "备选转正"));
            } else {
                skippedExisting++;
                ledger.add(row(t, v, "库中已有，不覆盖"));
            }
        }

        // 日志要能区分「没写」的每一种原因，否则「落库 0 条」会被误读成失败
        log.info("术语分诊：新增启用 {} / 新增备选 {} / 备选转正 {} / 库中已有 {} / 仅本次 {} / 丢弃 {}；"
                        + "本次可用 {} 条（含历史库共 {} 条参与翻译）",
                activated, candidates, promoted, skippedExisting, ephemeral, discarded,
                usable.size(), history.size() + usable.size());
        return TermBundle.of(history, usable, activated + candidates + promoted, indexable, ledger);
    }

    /** 明细一行：词 → 译法｜去向｜证据档｜理由。运行现场与 LangSmith 都读它。 */
    private static String row(ScoredTerm t, TermTriage.Verdict v, String outcome) {
        return t.source() + " → " + t.target() + "｜" + outcome
                + "｜" + v.tier().zh() + "｜" + v.reason();
    }

    /** 两个译法是否相同（trim + 小写，与 Arbitrator 的译法比较口径一致）。 */
    static boolean sameTarget(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
    }

    private void apply(GlossaryTerm g, ScoredTerm t, TermTriage.Verdict v) {
        g.setSourceTerm(t.source());
        g.setTargetTerm(t.target());
        g.setSourceNorm(t.sourceNorm());
        g.setStatus(v.state().name());
        g.setEnabled(v.state().enabledFlag());
        g.setEvidence(truncate(t.evidence(), 1000));
        g.setStrategy(t.strategy() == null ? null : t.strategy().name());
        g.setProfileCode(t.profileCode());
        g.setNote(truncate(buildNote(t, v), 500));
    }

    /**
     * note 即术语库里的「说明」：向用户解释这个词指什么、以及为什么是这个去向。
     *
     * <p>后半句是不打分换来的直接好处——旧版只能在这写个「置信度 0.73」，
     * 用户看了也不知道该拿它怎么办；现在写的是「音译类译法，无标准答案，须锁定全篇一致；
     * 暂无旁证，待你确认」，用户一眼就知道该点头还是该改。
     */
    static String buildNote(ScoredTerm t, TermTriage.Verdict v) {
        StringBuilder sb = new StringBuilder();
        if (t.reason() != null && !t.reason().isBlank()) {
            sb.append(t.reason().trim());
        }
        if (v != null && v.reason() != null && !v.reason().isBlank()) {
            if (sb.length() > 0) {
                sb.append("｜");
            }
            sb.append(v.reason());
        }
        return sb.toString();
    }

    /**
     * 取术语桶：每个「用户 × 领域」一个 kb_project。
     * <p>按 (userId, domainCode) 定位而<b>不是按 name</b>——name 用户可改，
     * 按名字找会在改名后静默新建一个桶并孤立掉之前所有术语。
     */
    public Long resolveBucket(Long userId, String domainCode, String domainName,
                              String sourceLang, String targetLang) {
        KbProject p = projectMapper.selectOne(Wrappers.<KbProject>lambdaQuery()
                .eq(KbProject::getUserId, userId)
                .eq(KbProject::getDomainCode, domainCode)
                .last("limit 1"));
        if (p != null) {
            return p.getId();
        }
        KbProject np = new KbProject();
        np.setUserId(userId);
        np.setName("【Agent】" + (domainName == null ? domainCode : domainName));
        np.setDomainCode(domainCode);
        np.setAutoCreated(1);
        np.setSourceLang(sourceLang == null ? "auto" : sourceLang);
        np.setTargetLang(targetLang == null ? "中文" : targetLang);
        projectMapper.insert(np);
        log.info("为用户 {} 的领域 {} 自动创建术语桶 #{}", userId, domainCode, np.getId());
        return np.getId();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String f = s.replaceAll("\\s+", " ").trim();
        return f.length() <= max ? f : f.substring(0, max);
    }
}
