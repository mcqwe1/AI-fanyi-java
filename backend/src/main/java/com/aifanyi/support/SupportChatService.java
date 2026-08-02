package com.aifanyi.support;

import com.aifanyi.agent.AgentHttp;
import com.aifanyi.agent.vector.EmbeddingClient;
import com.aifanyi.llm.LlmConfig;
import com.aifanyi.service.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 智能客服（传统 RAG）：使用手册切块 → 向量化 → 检索 top-K → 塞进提示词让模型回答。
 *
 * <p><b>知识库</b>是随 jar 分发的 {@code help/manual.md}——和程序同版本构建，
 * 永远描述的是用户手上这一版软件，不存在文档漂移。
 *
 * <p><b>降级链</b>（照全项目的哲学，客服永远给得出回应）：
 * ai-service 向量不可用 → 关键词打分检索；翻译模型没配 → 直接回引导语；
 * 模型调用失败 → 人话错误提示。任何一层坏掉都不抛异常。
 *
 * <p>回答模型复用「翻译模型」配置（每个能用的用户必已配好，无需新增设置项）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupportChatService {

    private static final int TOP_K = 5;
    /** 送给模型的历史轮数上限（一问一答算两条） */
    private static final int MAX_HISTORY = 8;
    private static final long TIMEOUT_MS = 60_000;

    private final EmbeddingClient embedding;
    private final SettingsService settings;
    private final AgentHttp http;
    private final ObjectMapper mapper;

    private volatile List<HelpChunker.Chunk> chunks;
    /** 与 chunks 对齐的向量；null = 向量不可用，检索走关键词兜底 */
    private volatile List<float[]> vectors;
    private final Object initLock = new Object();

    /** 一次回答：正文 + 引用的手册章节。 */
    public record Reply(String answer, List<String> sources) {
    }

    public Reply chat(Long userId, String question, List<Map<String, String>> history) {
        List<HelpChunker.Chunk> hits = retrieve(question);
        List<String> sources = hits.stream().map(HelpChunker.Chunk::title).distinct().toList();

        LlmConfig cfg = settings.effectiveLlm(userId);
        if (cfg == null || cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            return new Reply("我需要借助 AI 翻译模型才能回答问题，而它还没配置。请到「设置 → API 密钥」"
                    + "填好 AI 翻译模型（推荐 DeepSeek：Base URL 填 https://api.deepseek.com、"
                    + "API Key 填 sk- 开头的钥匙、模型填 deepseek-v4）——这也是软件能开始翻译的第一步。",
                    sources);
        }
        try {
            String answer = ask(cfg, question, history, hits);
            if (answer == null || answer.isBlank()) {
                return new Reply("模型没有给出回答，请换个问法再试一次。", sources);
            }
            return new Reply(answer.strip(), sources);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new Reply("请求被中断了，请再试一次。", sources);
        } catch (Exception e) {
            log.warn("客服回答失败: {}", e.toString());
            return new Reply("暂时联系不上 AI 模型（" + brief(e.getMessage())
                    + "）。请检查「设置 → API 密钥」里的翻译模型配置与网络后重试。", sources);
        }
    }

    // ─────────────────────────── 检索 ───────────────────────────

    private List<HelpChunker.Chunk> retrieve(String question) {
        ensureIndex();
        List<HelpChunker.Chunk> all = chunks;
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<float[]> vecs = vectors;
        if (vecs != null) {
            List<float[]> q = embedding.embed(List.of(question));
            if (q.size() == 1) {
                return topKByCosine(all, vecs, q.get(0));
            }
            // 查询向量化临时失败 → 本次退关键词，不动已建好的索引
        }
        return topKByKeyword(all, question);
    }

    private static List<HelpChunker.Chunk> topKByCosine(List<HelpChunker.Chunk> all,
                                                        List<float[]> vecs, float[] q) {
        record Scored(HelpChunker.Chunk c, float s) {
        }
        List<Scored> scored = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            float[] v = vecs.get(i);
            float dot = 0;
            int n = Math.min(v.length, q.length);
            for (int j = 0; j < n; j++) {
                dot += v[j] * q[j];                 // 向量已 L2 归一化，点积即余弦
            }
            scored.add(new Scored(all.get(i), dot));
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> s.s).reversed());
        return scored.subList(0, Math.min(TOP_K, scored.size())).stream().map(Scored::c).toList();
    }

    private static List<HelpChunker.Chunk> topKByKeyword(List<HelpChunker.Chunk> all, String q) {
        record Scored(HelpChunker.Chunk c, int s, int order) {
        }
        List<Scored> scored = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            scored.add(new Scored(all.get(i), HelpChunker.keywordScore(q, all.get(i)), i));
        }
        // 分高优先，同分保持文档顺序（确定性）
        scored.sort(Comparator.comparingInt((Scored s) -> -s.s).thenComparingInt(Scored::order));
        List<HelpChunker.Chunk> out = new ArrayList<>();
        for (Scored s : scored) {
            if (s.s <= 0 || out.size() >= TOP_K) {
                break;
            }
            out.add(s.c);
        }
        // 一个词都没命中：给手册开头几节兜底，让模型至少知道软件是什么
        if (out.isEmpty()) {
            return all.subList(0, Math.min(3, all.size()));
        }
        return out;
    }

    /** 懒加载：首次提问时读手册、切块、批量向量化。失败只影响检索方式，不影响可用性。 */
    private void ensureIndex() {
        if (chunks != null) {
            return;
        }
        synchronized (initLock) {
            if (chunks != null) {
                return;
            }
            List<HelpChunker.Chunk> cs;
            try {
                String md = new String(new ClassPathResource("help/manual.md")
                        .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                cs = HelpChunker.chunk(md);
            } catch (Exception e) {
                log.error("使用手册加载失败，客服将没有知识库: {}", e.toString());
                chunks = List.of();
                return;
            }
            List<float[]> vecs = embedding.embed(cs.stream().map(HelpChunker.Chunk::text).toList());
            if (vecs.size() == cs.size()) {
                vectors = vecs;
                log.info("客服知识库就绪：{} 块（向量检索）", cs.size());
            } else {
                vectors = null;
                log.info("客服知识库就绪：{} 块（向量不可用，关键词检索兜底）", cs.size());
            }
            chunks = cs;                            // 最后赋值：可见即完整
        }
    }

    // ─────────────────────────── 生成 ───────────────────────────

    private String ask(LlmConfig cfg, String question, List<Map<String, String>> history,
                       List<HelpChunker.Chunk> hits) throws Exception {
        StringBuilder sys = new StringBuilder();
        sys.append("你是「AI 视频翻译」软件的智能客服。根据下面的手册片段回答用户的使用问题。\n")
                .append("要求：\n")
                .append("1. 用中文回答，直接给操作步骤，简短清楚，不堆废话；\n")
                .append("2. 手册片段里没有的信息不要编造——如实说手册没覆盖，")
                .append("并建议用户去「设置」或右上角「使用教程」页看看；\n")
                .append("3. 与本软件无关的问题，礼貌说明你只负责本软件的使用咨询。\n\n")
                .append("【手册片段】\n");
        for (HelpChunker.Chunk c : hits) {
            sys.append(c.text()).append("\n\n");
        }

        ObjectNode req = mapper.createObjectNode();
        req.put("model", cfg.model());
        req.put("temperature", 0.3);
        if (cfg.disableThinking()) {
            req.putObject("thinking").put("type", "disabled");
        }
        ArrayNode messages = req.putArray("messages");
        messages.addObject().put("role", "system").put("content", sys.toString());
        appendHistory(messages, history);
        messages.addObject().put("role", "user").put("content", question);

        AgentHttp.Result r = http.postJson(
                AgentHttp.join(cfg.baseUrl(), "/chat/completions"),
                Map.of("Authorization", "Bearer " + cfg.apiKey()),
                mapper.writeValueAsString(req), TIMEOUT_MS);
        if (!r.ok()) {
            throw new IllegalStateException("HTTP " + r.status());
        }
        JsonNode root = mapper.readTree(r.body());
        return root.path("choices").path(0).path("message").path("content").asText("");
    }

    /** 历史消息只收 user/assistant 两种角色、只留最近几轮（防把 system 混进来或撑爆上下文）。 */
    private static void appendHistory(ArrayNode messages, List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return;
        }
        int from = Math.max(0, history.size() - MAX_HISTORY);
        for (int i = from; i < history.size(); i++) {
            Map<String, String> m = history.get(i);
            String role = m.get("role");
            String content = m.get("content");
            if (content == null || content.isBlank()
                    || (!"user".equals(role) && !"assistant".equals(role))) {
                continue;
            }
            messages.addObject().put("role", role)
                    .put("content", content.length() > 2000 ? content.substring(0, 2000) : content);
        }
    }

    private static String brief(String s) {
        if (s == null) {
            return "未知错误";
        }
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
