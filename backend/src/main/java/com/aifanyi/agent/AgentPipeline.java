package com.aifanyi.agent;

import com.aifanyi.agent.llm.AgentJsonClient;
import com.aifanyi.agent.model.*;
import com.aifanyi.agent.node.*;
import com.aifanyi.agent.profile.AgentProfile;
import com.aifanyi.agent.search.SearchConfig;
import com.aifanyi.agent.source.DigestBuilder;
import com.aifanyi.agent.source.SourceLoader;
import com.aifanyi.agent.trace.TraceRecorder;
import com.aifanyi.config.AifanyiProperties;
import com.aifanyi.domain.TaskStatus;
import com.aifanyi.entity.Subtitle;
import com.aifanyi.entity.TranslationTask;
import com.aifanyi.llm.LlmConfig;
import com.aifanyi.mapper.SubtitleMapper;
import com.aifanyi.mapper.TranslationTaskMapper;
import com.aifanyi.media.SrtService;
import com.aifanyi.service.SettingsService;
import com.aifanyi.storage.StorageService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 模式流水线（全能 AI 翻译）。
 *
 * <pre>
 * ① 路由（TaskService 已按扩展名判定，纯代码零 token）
 * ② SourceLoader → SourceDoc（垃圾源在此拦截，唯一允许终止点）
 * ③ SceneInferencer 场景推测（主 Agent，1 次 LLM，结果缓存）
 * ④ AgentFanout 子 Agent 扇出（并行，全局 deadline）
 * ⑤ Arbitrator 去重 + 仲裁 + 置信度算分
 * ⑦ TermStateMachine 分档落库 → TermBundle
 * ⑧ RagContextBuilder 分组串行/组间并发翻译
 * ⑨ 出口：有时间轴出 SRT，无时间轴只落字幕表（TXT 下载复用现有接口）
 * ⑩ Trace 全程落库
 * </pre>
 *
 * <p><b>降级总则</b>：除②垃圾源外，任何单点故障都不终止任务——
 * 场景推测失败→通用档案；子 Agent 全挂→空术语表，翻译退化为普通模式质量。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentPipeline {

    private final TranslationTaskMapper taskMapper;
    private final SubtitleMapper subtitleMapper;
    private final StorageService storage;
    private final SettingsService settings;
    private final SourceLoader sourceLoader;
    private final SceneInferencer sceneInferencer;
    private final AgentFanout fanout;
    private final Arbitrator arbitrator;
    private final TermStateMachine termStateMachine;
    private final RagContextBuilder ragTranslator;
    private final com.aifanyi.agent.vector.VectorIndexService vectorIndex;
    private final TraceRecorder trace;
    private final com.aifanyi.agent.trace.LangSmithExporter langSmith;
    private final AifanyiProperties props;
    private final com.aifanyi.service.KbService kbService;

    /**
     * 由 TaskService 调用（不可自调用——@Async 自调用会绕过代理变成同步执行）。
     */
    @Async("taskExecutor")
    public void runAsync(Long taskId) {
        TranslationTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.error("任务不存在: {}", taskId);
            return;
        }
        trace.reset(taskId);            // 重试复用同一 taskId，先清上一轮时间线
        TraceRecorder.Buffer buf = trace.newBuffer(taskId);
        long t0 = System.currentTimeMillis();
        // LangSmith 云端观测：配了 Key 才开（隐私默认关闭；上报含完整台词，必须用户显式选择）
        java.util.Map<String, Object> lsOut = new java.util.LinkedHashMap<>();
        try {
            String[] ls = settings.effectiveLangsmith(task.getUserId());
            if (ls != null) {
                buf.lsCtx = langSmith.begin(ls[0], ls[1], "全能翻译#" + taskId,
                        java.util.Map.of("file", String.valueOf(task.getOriginalFilename()),
                                "sourceLang", String.valueOf(task.getSourceLang()),
                                "targetLang", String.valueOf(task.getTargetLang())));
            }
        } catch (Exception e) {
            log.debug("LangSmith 初始化失败（忽略）: {}", e.toString());
        }
        try {
            AifanyiProperties.Agent acfg = props.getAgent();
            Long uid = task.getUserId();

            // ── ② 处理器链 ──
            update(task, TaskStatus.EXTRACTING_AUDIO, 10, "读取源文件");
            final TranslationTask pt = task;
            SourceDoc doc = sourceLoader.load(task,
                    () -> taskMapper.updateById(pt),
                    ratio -> {
                        int pct = 15 + (int) Math.round(ratio * 35);
                        bumpProgress(pt, pct);
                    });
            if (doc.hasTimeline()) {
                update(task, TaskStatus.TRANSCRIBING, 50, "转写完成");
            }
            log.info("Agent 任务 {}：{} 分片，{} 字", taskId, doc.size(), doc.fullText().length());

            String digest = DigestBuilder.build(doc, acfg.getDigestChars());

            // ── ③ 场景推测 ──
            update(task, TaskStatus.INFERRING_SCENE, 55, "识别内容领域");
            LlmConfig mainLlm = settings.effectiveAgentMain(uid);
            SceneInferencer.SceneResult scene = sceneInferencer.infer(
                    digest, doc.sourceLang(), doc.targetLang(), mainLlm, uid, buf);
            task.setAgentDomain(scene.domainCodes());
            taskMapper.updateById(task);
            trace.flush(buf);           // 场景条目即时可见（子 Agent 内部各步自行 flush）

            // ── ④ 子 Agent 扇出 ──
            update(task, TaskStatus.AGENT_TERMS, 60, "领域专家抽取术语");
            LlmConfig subLlm = settings.effectiveAgentSub(uid);
            SearchConfig searchCfg = settings.effectiveSearch(uid);
            if (!searchCfg.enabled()) {
                log.info("未配置联网搜索，子 Agent 走「上下文证据 + 策略五选一」路径");
            }
            List<AgentProfile> profiles = scene.matches().stream()
                    .map(SceneInferencer.SceneMatch::profile).toList();
            SubAgentTask.SubAgentContext base = new SubAgentTask.SubAgentContext(
                    profiles.get(0), digest, doc.fullText(), doc.sourceLang(), doc.targetLang(),
                    subLlm, searchCfg, acfg.getMaxSearchPerAgent());

            Deadline global = Deadline.in(Duration.ofSeconds(acfg.getFanoutDeadlineSec()));
            AgentFanout.FanoutResult fr = fanout.run(profiles, base, global, buf,
                    n -> bumpProgress(pt, 60 + n * 10 / Math.max(1, profiles.size())));

            // ── ⑤ 仲裁 ──
            long a0 = System.currentTimeMillis();
            List<String> order = profiles.stream().map(AgentProfile::getDomainCode).toList();
            List<ScoredTerm> scored = arbitrator.arbitrate(fr.results(), order);
            trace.record(buf, "ARBITRATE", null, fr.termCount() + " 条候选",
                    scored.size() + " 条唯一术语", System.currentTimeMillis() - a0, "OK", false);
            trace.stepFull(buf, "术语仲裁",
                    TraceRecorder.fields("候选", fr.termCount() + " 条（来自 " + profiles.size() + " 个专家）"),
                    TraceRecorder.fields("唯一术语", scored.size() + " 条", "术语表", renderScored(scored)),
                    System.currentTimeMillis() - a0, false);
            trace.flush(buf);
            bumpProgress(task, 72);

            // ── ⑦ 术语状态机 + 落库 ──
            TermBundle bundle = TermBundle.empty();
            try {
                AgentProfile primary = profiles.get(0);
                // 入库目标：用户在提交时指定了术语库就写进去（明确的沉淀意图），
                // 没指定才按领域自动建桶。复用 task.project_id 这一列，零 schema 改动。
                Long bucketId = task.getProjectId() != null
                        ? task.getProjectId()
                        : termStateMachine.resolveBucket(uid, primary.getDomainCode(),
                                primary.getName(), doc.sourceLang(), doc.targetLang());
                // 回写术语桶：运行详情面板与「下次同领域视频复用术语」都靠它
                task.setProjectId(bucketId);
                taskMapper.updateById(task);
                // 近义发现：标注库里疑似同词族的已有条目（仅提示，不合并、不删数据）。
                // 必须在落库前做——落库后新词自己也进了库，会跟自己算成近义。
                scored = vectorIndex.annotateRelated(scored, bucketId);
                bundle = termStateMachine.persist(scored, bucketId, taskId);
                // 向量化放在事务外：它是一次可能几十秒的 HTTP，
                // 塞进 @Transactional 会把 H2 的写锁按住同样久（H2 是单写文件库）
                vectorIndex.index(bucketId, bundle.indexable());
                trace.record(buf, "TERMS", null, scored.size() + " 条候选",
                        "本次落库 " + bundle.persisted() + " 条，参与翻译 " + bundle.size()
                                + " 条（含历史库复用）", 0, "OK", false);
                trace.stepFull(buf, "术语落库",
                        TraceRecorder.fields("候选", scored.size() + " 条"),
                        persistBreakdown(bundle), 0, false);
            } catch (Exception e) {
                // 术语落库失败 → 裸翻，绝不因此丢掉整个任务
                log.warn("术语入库失败，本次裸翻: {}", e.getMessage());
                trace.record(buf, "TERMS", null, "", "失败: " + e.getMessage(), 0, "ERROR", true);
                trace.stepFull(buf, "术语落库", TraceRecorder.fields(),
                        TraceRecorder.fields("失败", String.valueOf(e.getMessage()),
                                "处置", "本次不带术语库翻译，任务继续"), 0, true);
            }
            trace.flush(buf);           // 术语条目即时可见，⑧ 翻译是最长的一段，别让现场干等
            bumpProgress(task, 75);

            // ── ⑧ RAG 翻译 ──
            update(task, TaskStatus.TRANSLATING, 76, "带术语翻译");
            // 用户勾选的术语库：明确的套用意图，覆盖同名的 Agent 产出
            Map<String, String> extraGlossary = kbService.loadForInjection(
                    uid, task.getGlossaryProjectIds(), null);
            if (!extraGlossary.isEmpty()) {
                bundle = bundle.withExtra(extraGlossary);
                trace.record(buf, "TERMS", null, "套用术语库",
                        "用户勾选术语 " + extraGlossary.size() + " 条并入翻译", 0, "OK", false);
            }
            LlmConfig transLlm = settings.effectiveAgentTranslate(uid);
            List<String> sources = doc.texts();
            long tr0 = System.currentTimeMillis();
            RagContextBuilder.RagResult rr = ragTranslator.translate(sources, doc.targetLang(),
                    transLlm, bundle, task.getStylePrompt(),
                    n -> bumpProgress(pt, Math.min(95, 76 + n)));
            List<String> targets = rr.targets();
            int missed = rr.missingLines().size();
            trace.record(buf, "TRANSLATE", null, sources.size() + " 行",
                    "注入术语 " + bundle.size() + " 条"
                            + (missed > 0 ? "；" + missed + " 行未译成保留原文（" + fmtLines(rr.missingLines()) + "）" : ""),
                    System.currentTimeMillis() - tr0, missed > 0 ? "PARTIAL" : "OK", missed > 0);
            trace.stepFull(buf, "带术语翻译",
                    TraceRecorder.fields("原文", sources.size() + " 行",
                            "注入术语", bundle.size() + " 条",
                            "注入清单", renderGlossary(bundle.map())),
                    missed > 0
                            ? TraceRecorder.fields("译文", targets.size() + " 行",
                                    "未译行", fmtLines(rr.missingLines()) + "（补翻三轮仍失败，已保留原文）")
                            : TraceRecorder.fields("译文", targets.size() + " 行"),
                    System.currentTimeMillis() - tr0, missed > 0);
            trace.flush(buf);

            // ── ⑨ 出口：落字幕表 + 生成 SRT（有时间轴时）──
            persistSubtitles(taskId, doc, targets);
            if (doc.hasTimeline()) {
                writeSrt(task, taskId);
            }

            boolean degraded = scene.degraded() || fr.degraded() || buf.anyDegraded();
            task.setAgentDegraded(degraded ? 1 : 0);
            update(task, TaskStatus.DONE, 100, null);
            lsOut.put("lines", targets.size());
            lsOut.put("terms", scored.size());
            lsOut.put("persisted", bundle.persisted());
            lsOut.put("domains", scene.domainCodes());
            lsOut.put("tokens", buf.totalTokens());
            lsOut.put("degraded", degraded);
            log.info("Agent 任务完成: {} —— {} 行字幕，术语 {} 条（落库 {}），领域 [{}]，"
                            + "总耗时 {}s，token {}{}",
                    taskId, targets.size(), scored.size(), bundle.persisted(), scene.domainCodes(),
                    (System.currentTimeMillis() - t0) / 1000,
                    buf.totalTokens(), degraded ? "，含降级" : "");
        } catch (Exception e) {
            log.error("Agent 任务失败: {}", taskId, e);
            task.setStatus(TaskStatus.FAILED.name());
            task.setErrorMsg(truncate(e.getMessage(), 900));
            task.setAgentPhase(null);
            taskMapper.updateById(task);
            trace.record(buf, "PIPELINE", null, "", "失败: " + e.getMessage(), 0, "ERROR", true);
            lsOut.put("failed", true);
            langSmith.end(buf.lsCtx, lsOut, e.getMessage());
            buf.lsCtx = null;                       // 已收尾，finally 不再重复
        } finally {
            langSmith.end(buf.lsCtx, lsOut, null);  // 成功路径在此收尾（null ctx 为空操作）
            trace.flush(buf);
        }
    }

    /** 落字幕表：文本任务无时间轴，start/end 记 0（TXT 下载复用现有接口）。 */
    private void persistSubtitles(Long taskId, SourceDoc doc, List<String> targets) {
        subtitleMapper.delete(Wrappers.<Subtitle>lambdaQuery().eq(Subtitle::getTaskId, taskId));
        List<Chunk> chunks = doc.chunks();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            Subtitle s = new Subtitle();
            s.setTaskId(taskId);
            s.setSeq(i + 1);
            s.setStartMs(c.startMs() == null ? 0 : c.startMs());
            s.setEndMs(c.endMs() == null ? 0 : c.endMs());
            s.setSourceText(c.text());
            s.setTargetText(i < targets.size() ? targets.get(i) : c.text());
            subtitleMapper.insert(s);
        }
    }

    private void writeSrt(TranslationTask task, Long taskId) {
        try {
            List<Subtitle> subs = subtitleMapper.selectList(Wrappers.<Subtitle>lambdaQuery()
                    .eq(Subtitle::getTaskId, taskId).orderByAsc(Subtitle::getSeq));
            String srt = task.getBilingual() != null && task.getBilingual() == 1
                    ? SrtService.toBilingualSrt(subs) : SrtService.toSrt(subs);
            Path p = storage.resolve(taskId, "subtitle.srt");
            Files.writeString(p, srt, StandardCharsets.UTF_8);
            task.setSrtPath(p.toString());
            taskMapper.updateById(task);
        } catch (Exception e) {
            // SRT 写失败不该让整单失败——字幕已在库里，编辑器可用，可重新导出
            log.warn("生成 SRT 失败（字幕已落库，不影响成果）: {}", e.getMessage());
        }
    }

    private void update(TranslationTask task, TaskStatus status, int progress, String phase) {
        task.setStatus(status.name());
        task.setProgress(progress);
        task.setAgentPhase(phase);
        taskMapper.updateById(task);
    }

    /** 单调递增的进度更新（并发回调下不回跳）。 */
    private void bumpProgress(TranslationTask task, int pct) {
        synchronized (task) {
            Integer cur = task.getProgress();
            if (cur == null || pct > cur) {
                task.setProgress(Math.min(99, pct));
                taskMapper.updateById(task);
            }
        }
    }

    // ────── LangSmith 明细渲染：人能读的清单（本地 record 只存条数摘要）──────

    /** ⑤ 仲裁产物：每行一条，译法/策略/来源/一致性/冲突一眼可读。 */
    private static String renderScored(List<ScoredTerm> scored) {
        if (scored.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (ScoredTerm t : scored) {
            sb.append(t.source()).append(" → ").append(t.target());
            if (t.strategy() != null) {
                sb.append('｜').append(t.strategy().zh());
            }
            if (t.hasAuthority()) {
                sb.append("｜权威佐证");
            } else if (t.agreements() >= 2) {
                sb.append("｜").append(t.agreements()).append(" 位专家一致");
            }
            if (t.profileCode() != null) {
                sb.append('｜').append(t.profileCode());
            }
            if (t.occurrences() > 1) {
                sb.append("｜出现 ").append(t.occurrences()).append(" 次");
            }
            if (t.hasConflict()) {
                sb.append("｜⚠ 其他译法: ").append(String.join("、", t.conflicts()));
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** ⑦ 落库结果：状态机记下的逐词去向（谁入库启用、谁进备选、谁只用这一次）。 */
    private static Map<String, Object> persistBreakdown(TermBundle bundle) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("本次落库", bundle.persisted() + " 条");
        out.put("参与翻译", bundle.size() + " 条（含历史库复用）");
        if (!bundle.ledger().isEmpty()) {
            out.put("逐词去向", String.join("\n", bundle.ledger()));
        }
        return out;
    }

    /** ⑧ 实际注入翻译 prompt 的术语映射（历史 + 本次，长词在前）。 */
    private static String renderGlossary(Map<String, String> glossary) {
        if (glossary.isEmpty()) {
            return "（本次未注入术语，等同普通翻译）";
        }
        StringBuilder sb = new StringBuilder();
        glossary.forEach((k, v) -> sb.append(k).append(" → ").append(v).append('\n'));
        return sb.toString().stripTrailing();
    }

    /** 行号清单 → 人话（转 1 基，连续段合并）：[80,81,82,96] → 「第 81~83、97 行」。 */
    private static String fmtLines(List<Integer> zeroBased) {
        if (zeroBased == null || zeroBased.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        int start = zeroBased.get(0);
        int prev = start;
        for (int k = 1; k < zeroBased.size(); k++) {
            int v = zeroBased.get(k);
            if (v == prev + 1) {
                prev = v;
                continue;
            }
            parts.add(fmtRange(start, prev));
            start = v;
            prev = v;
        }
        parts.add(fmtRange(start, prev));
        return "第 " + String.join("、", parts) + " 行";
    }

    private static String fmtRange(int s, int e) {
        return s == e ? String.valueOf(s + 1) : (s + 1) + "~" + (e + 1);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
