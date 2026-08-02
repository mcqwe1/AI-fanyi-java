package com.aifanyi.agent.node;

import com.aifanyi.agent.AgentBudget;
import com.aifanyi.agent.Deadline;
import com.aifanyi.agent.llm.AgentJsonClient;
import com.aifanyi.agent.llm.Prompts;
import com.aifanyi.agent.profile.AgentProfile;
import com.aifanyi.agent.profile.ProfileService;
import com.aifanyi.agent.trace.TraceRecorder;
import com.aifanyi.config.AifanyiProperties;
import com.aifanyi.llm.LlmConfig;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * ③ 主 Agent · 场景推测：在档案池里做匹配（而非在写死的 Agent 里做选择）。
 *
 * <p>只跑一次、只看摘要，结果缓存到任务上（重试不重跑）。
 * <p><b>主 Agent 无联网工具</b>——模型名的 -search 后缀在 AgentJsonClient 里被强制剥除，
 * 这是架构「物理防慢」要求的代码化：让主 Agent 想慢都慢不了。
 * <p>失败一律降级到 general 兜底档案，绝不让翻译因为「不知道这是什么领域」而中断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SceneInferencer {

    /** 匹配分低于此值不激活对应子 Agent */
    private static final double MIN_SCORE = 0.35;

    private final AgentJsonClient llm;
    private final ProfileService profiles;
    private final AifanyiProperties props;
    private final TraceRecorder trace;

    /** 一次命中：档案 + 匹配分 + 依据。 */
    public record SceneMatch(AgentProfile profile, double score, String why) {
    }

    /** 推测结果：命中列表 + 是否降级。 */
    public record SceneResult(List<SceneMatch> matches, boolean degraded, long tokens) {
        public String domainCodes() {
            return String.join(",", matches.stream().map(m -> m.profile().getDomainCode()).toList());
        }
    }

    /**
     * 推测内容领域。
     *
     * @param digest     文本摘要（首段即可，不必全文）
     * @param mainLlm    主 Agent 模型配置
     * @param userId     用于取该用户的档案池
     */
    public SceneResult infer(String digest, String sourceLang, String targetLang,
                             LlmConfig mainLlm, Long userId, TraceRecorder.Buffer traceBuf) {
        List<AgentProfile> pool = profiles.pool(userId);
        AifanyiProperties.Agent cfg = props.getAgent();
        Deadline dl = Deadline.in(Duration.ofSeconds(cfg.getSceneTimeoutSec()));
        AgentBudget budget = AgentBudget.of(dl, 1, 0, 0);

        AgentJsonClient.Prompt prompt = Prompts.sceneInfer(digest, pool, sourceLang, targetLang);
        AgentJsonClient.Call call = llm.callJson(prompt, mainLlm, budget, "SCENE");
        // LangSmith 全文上报（未启用时零开销）：完整提示词+完整返回，供云端细查
        trace.llmFull(traceBuf, "场景推测", mainLlm.model(), prompt.system(), prompt.user(),
                call.ok() ? call.json().toString() : call.stopReason(), call.stopReason(),
                call.elapsedMs(), call.promptTokens(), call.completionTokens());

        if (!call.ok()) {
            // 降级：用 general 继续。术语质量下降，但翻译不中断
            log.warn("场景推测失败（{}），降级到通用档案", call.stopReason());
            trace.record(traceBuf, "SCENE", null, "infer", "失败→降级 general",
                    call.elapsedMs(), call.promptTokens(), call.completionTokens(),
                    call.totalTokens(), call.stopReason(), true);
            return new SceneResult(List.of(new SceneMatch(profiles.fallback(userId), 1.0, "推测失败兜底")),
                    true, call.totalTokens());
        }

        List<SceneMatch> matches = parseMatches(call.json(), pool, userId, cfg.getMaxSubAgents());
        if (matches.isEmpty()) {
            log.info("场景推测未命中任何档案，使用通用档案");
            matches = List.of(new SceneMatch(profiles.fallback(userId), 1.0, "未命中"));
        }
        String summary = matches.stream()
                .map(m -> m.profile().getDomainCode() + "(" + String.format("%.2f", m.score()) + ")")
                .reduce((a, b) -> a + ", " + b).orElse("");
        log.info("场景推测命中：{}", summary);
        trace.record(traceBuf, "SCENE", null, "infer:" + digest.length() + "字", summary,
                call.elapsedMs(), call.promptTokens(), call.completionTokens(),
                call.totalTokens(), "OK", false);
        return new SceneResult(matches, false, call.totalTokens());
    }

    private List<SceneMatch> parseMatches(JsonNode root, List<AgentProfile> pool,
                                          Long userId, int maxSubAgents) {
        List<SceneMatch> out = new ArrayList<>();
        JsonNode arr = root.path("matches");
        if (arr.isArray()) {
            for (JsonNode m : arr) {
                String code = m.path("domain").asText("").trim();
                double score = m.path("score").asDouble(0);
                if (code.isBlank() || score < MIN_SCORE) {
                    continue;
                }
                AgentProfile p = pool.stream()
                        .filter(x -> x.getDomainCode().equalsIgnoreCase(code))
                        .findFirst().orElse(null);
                if (p != null) {
                    out.add(new SceneMatch(p, score, m.path("why").asText("")));
                }
            }
        }
        // 未命中已有档案时，模型可能给了新领域草稿 → 入库并本次即用
        if (out.isEmpty()) {
            JsonNode d = root.path("draft");
            if (d.isObject() && !d.path("domainCode").asText("").isBlank()) {
                AgentProfile draft = profiles.saveDraft(userId,
                        d.path("domainCode").asText(), d.path("name").asText(),
                        d.path("judgeCriteria").asText(), d.path("conventions").asText(),
                        d.path("searchHints").asText());
                if (draft != null) {
                    out.add(new SceneMatch(draft, 0.8, "新建领域草稿"));
                }
            }
        }
        // 按分降序，封顶实例数：档案表无上限，但每个实例=2次LLM+2次搜索，必须封顶
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out.size() > maxSubAgents ? out.subList(0, maxSubAgents) : out;
    }
}
