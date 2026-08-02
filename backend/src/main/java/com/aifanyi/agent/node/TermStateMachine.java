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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⑦ 术语状态机 + 落库（harness 状态与记忆层）。
 *
 * <p>分档：≥0.85 verified / 0.6~0.85 pending / 0.4~0.6 仅本次注入 / <0.4 丢弃。
 *
 * <p><b>裁定架构图的一处自相矛盾</b>：它既说「0.4~0.6 不入库」，又说「无答案策略词
 * → unverified 入库（价值=锁定一致性）」，而策略词的分数通常正落在那一档。
 * 这里让<b>策略词规则优先</b>——记录一个音译/造词的意义不在于它「正确」，
 * 而在于全片<b>统一</b>；不入库就锁不住下一集视频的一致性，那才是真正的损失。
 *
 * <p><b>跨任务确认飞轮</b>：单任务算分的天花板故意压在 0.82（同一个模型换几顶专家帽子
 * 达成的「一致」不算独立证据），所以没有权威来源时一次任务永远到不了 0.85 的自动确认线。
 * 升「已确认」的正路在这里：同词同译在<b>另一单任务</b>里再次出现（不同素材、不同摘要、
 * 独立采样，这才是真证据），每次 +0.06、封顶 0.88——连着三单一致就自动转正。
 * 按 last_task_id 防同一任务重试刷分。
 *
 * <p>冲突优先级（用户手动 > 历史库 > 本次新提取）是<b>持久化前的合并遍</b>，
 * 不是 upsert 标志——已被用户确认的条目连 update 都不做。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TermStateMachine {

    private static final double T_VERIFIED = 0.85;
    private static final double T_PENDING = 0.60;
    private static final double T_EPHEMERAL = 0.40;
    /** 跨任务确认：每次独立任务的同译确认加这么多分 */
    private static final double CONFIRM_STEP = 0.06;
    /** 跨任务确认的封顶：高于自动确认线（0.85），但到不了权威来源的档位（0.95） */
    private static final double CONFIRM_CAP = 0.88;

    private final GlossaryTermMapper termMapper;
    private final KbProjectMapper projectMapper;

    /** 纯函数分档，可单测。 */
    public static TermState classify(ScoredTerm t) {
        double s = t.confidence();
        if (s < T_EPHEMERAL) {
            return TermState.DISCARD;
        }
        // 策略词优先：无权威答案但已定策略的词，即使分数落在「仅本次」档也要入库锁一致性
        if (t.strategy() != null && t.strategy().isCoined() && s >= T_EPHEMERAL) {
            return s >= T_VERIFIED ? TermState.VERIFIED
                    : (s >= T_PENDING ? TermState.PENDING : TermState.UNVERIFIED);
        }
        if (s >= T_VERIFIED) {
            return TermState.VERIFIED;
        }
        if (s >= T_PENDING) {
            return TermState.PENDING;
        }
        return TermState.EPHEMERAL;
    }

    /**
     * 分档 → 落库 → 产出本次可用的术语集合。
     *
     * @param scored    仲裁后的术语
     * @param projectId 术语桶（kb_project）id
     * @param taskId    本次任务 id（跨任务确认飞轮据此防重试刷分）
     * @return 本次翻译可用的 TermBundle（含历史库 + 新产出 + EPHEMERAL）
     */
    @Transactional
    public TermBundle persist(List<ScoredTerm> scored, Long projectId, Long taskId) {
        // 历史库：本项目已启用的条目（含用户手工确认的）
        List<GlossaryTerm> existing = termMapper.selectList(
                Wrappers.<GlossaryTerm>lambdaQuery().eq(GlossaryTerm::getProjectId, projectId));
        Map<String, GlossaryTerm> byNorm = new LinkedHashMap<>();
        Map<String, String> history = new LinkedHashMap<>();
        for (GlossaryTerm g : existing) {
            String key = g.getSourceNorm() != null ? g.getSourceNorm() : Arbitrator.norm(g.getSourceTerm());
            byNorm.put(key, g);
            if (g.getEnabled() != null && g.getEnabled() == 1
                    && g.getSourceTerm() != null && g.getTargetTerm() != null) {
                history.put(g.getSourceTerm(), g.getTargetTerm());
            }
        }

        List<ScoredTerm> usable = new ArrayList<>();
        List<GlossaryTerm> indexable = new ArrayList<>();
        int inserted = 0;
        int updated = 0;
        int confirmed = 0;
        int skippedManual = 0;
        int unchanged = 0;
        int ephemeral = 0;
        int discarded = 0;

        for (ScoredTerm t : scored) {
            TermState st = classify(t);
            if (st == TermState.DISCARD) {
                discarded++;
                continue;
            }
            usable.add(t);                     // EPHEMERAL 也参与本次注入
            if (!st.persistent()) {
                ephemeral++;
                continue;                      // 仅本次可用，不落库
            }

            GlossaryTerm old = byNorm.get(t.sourceNorm());
            if (old != null) {
                // 冲突优先级：用户手动 / 已确认的条目一律不动（数据飞轮的基石）
                if ("manual".equals(old.getOrigin())
                        || TermState.VERIFIED.name().equals(old.getStatus())) {
                    skippedManual++;
                    continue;
                }
                double oldScore = old.getConfidence() == null ? 0 : old.getConfidence().doubleValue();
                // 跨任务确认飞轮：同词同译在另一单任务里再次出现 → 独立确认，加分
                if (sameTarget(old.getTargetTerm(), t.target())) {
                    if (taskId != null && taskId.equals(old.getLastTaskId())) {
                        unchanged++;           // 同一任务重跑不算确认，防重试刷分
                        continue;
                    }
                    double bumped = Math.min(CONFIRM_CAP,
                            Math.max(oldScore, t.confidence()) + CONFIRM_STEP);
                    if (bumped <= oldScore) {
                        unchanged++;
                        continue;
                    }
                    ScoredTerm boosted = t.withConfidence(bumped);
                    apply(old, boosted, classify(boosted));
                    old.setNote(truncate(old.getNote() + "（跨任务译法一致，自动加分）", 500));
                    old.setLastTaskId(taskId);
                    termMapper.updateById(old);
                    confirmed++;
                    continue;
                }
                // 译法不同：仅当新分数更高才更新，避免反复横跳
                if (t.confidence() <= oldScore) {
                    unchanged++;
                    continue;
                }
                apply(old, t, st);
                old.setLastTaskId(taskId);
                termMapper.updateById(old);
                updated++;
            } else {
                GlossaryTerm g = new GlossaryTerm();
                g.setProjectId(projectId);
                g.setOrigin("agent");
                g.setEnabled(1);
                g.setVectorStatus(0);
                g.setLastTaskId(taskId);
                apply(g, t, st);
                termMapper.insert(g);
                byNorm.put(t.sourceNorm(), g);
                indexable.add(g);              // 事务外再向量化（见 TermBundle.indexable）
                inserted++;
            }
        }

        // 日志要能区分「没写」的几种原因，否则「落库 0 条」会被误读成失败
        log.info("术语状态机：新增 {} / 更新 {} / 跨任务确认 {} / 已有更优 {} / 已确认跳过 {} / "
                        + "仅本次 {} / 丢弃 {}；本次可用 {} 条（含历史库共 {} 条参与翻译）",
                inserted, updated, confirmed, unchanged, skippedManual, ephemeral, discarded,
                usable.size(), history.size() + usable.size());
        return TermBundle.of(history, usable, inserted + updated + confirmed, indexable);
    }

    /** 译法等价比较：忽略首尾空白与大小写（中文不受影响，拉丁译名 API/api 视为同译）。 */
    private static boolean sameTarget(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().toLowerCase(java.util.Locale.ROOT)
                .equals(b.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private void apply(GlossaryTerm g, ScoredTerm t, TermState st) {
        g.setSourceTerm(t.source());
        g.setTargetTerm(t.target());
        g.setSourceNorm(t.sourceNorm());
        g.setConfidence(BigDecimal.valueOf(t.confidence()).setScale(3, RoundingMode.HALF_UP));
        g.setStatus(st.name());
        g.setEvidence(truncate(t.evidence(), 1000));
        g.setStrategy(t.strategy() == null ? null : t.strategy().name());
        g.setProfileCode(t.profileCode());
        g.setNote(buildNote(t));
        // 待确认与策略词默认仍启用（本次翻译要用），用户可在术语库页关掉
        if (g.getEnabled() == null) {
            g.setEnabled(1);
        }
    }

    /** note 承载人类可读的判断依据，直接显示在现有术语库 UI 里。 */
    private String buildNote(ScoredTerm t) {
        StringBuilder sb = new StringBuilder();
        if (t.strategy() != null) {
            sb.append('[').append(t.strategy().zh()).append("] ");
        }
        if (t.reason() != null && !t.reason().isBlank()) {
            sb.append(t.reason());
        }
        if (t.hasConflict()) {
            sb.append("（存在其他译法：").append(String.join("、", t.conflicts())).append("，请确认）");
        }
        if (t.occurrences() > 1) {
            sb.append("（全文出现 ").append(t.occurrences()).append(" 次）");
        }
        return truncate(sb.toString(), 500);
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
