package com.aifanyi.llm;

import com.aifanyi.common.BizException;
import com.aifanyi.config.AifanyiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * OpenAI 兼容的翻译实现（可接 OpenAI / anyrouter 中转 / DeepSeek / 通义 / 智谱）。
 * 关键优化：推理模型关闭思维链提速、多批次并发、强制 json_object、严格保持行数顺序。
 * 配置来自每次调用传入的 LlmConfig（用户设置优先，env 兜底）。
 */
@Slf4j
@Component
public class OpenAiTranslator implements LlmTranslator {

    private final RestClient client;
    private final ObjectMapper mapper;
    private final ExecutorService pool;

    public OpenAiTranslator(AifanyiProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        this.client = RestClient.create();
        int c = Math.max(1, props.getLlm().getConcurrency());
        this.pool = Executors.newFixedThreadPool(c, r -> {
            Thread t = new Thread(r, "llm-translate");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    @Override
    public List<String> translate(List<String> sources, String targetLang, LlmConfig cfg,
                                  java.util.Map<String, String> glossary, String stylePrompt) {
        if (!StringUtils.hasText(cfg.apiKey())) {
            throw new BizException("未配置翻译模型 API Key，请在设置里填写或用环境变量");
        }
        java.util.Map<String, String> gloss = glossary == null ? java.util.Map.of() : glossary;
        String style = StringUtils.hasText(stylePrompt) ? stylePrompt.trim() : null;
        int batchSize = Math.max(1, cfg.batchSize());

        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < sources.size(); i += batchSize) {
            batches.add(sources.subList(i, Math.min(i + batchSize, sources.size())));
        }

        List<Future<List<String>>> futures = new ArrayList<>(batches.size());
        for (List<String> batch : batches) {
            futures.add(pool.submit(() -> translateBatch(batch, targetLang, cfg, gloss, style)));
        }

        long t0 = System.currentTimeMillis();
        List<String> out = new ArrayList<>(sources.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                out.addAll(futures.get(i).get());
            } catch (Exception e) {
                log.warn("第{}批翻译失败，保留原文: {}", i, e.getMessage());
                out.addAll(batches.get(i));
            }
        }
        log.info("翻译完成: {} 行 / {} 批, 模型 {}, 总耗时 {}s",
                sources.size(), batches.size(), cfg.model(),
                String.format(java.util.Locale.ROOT, "%.1f", (System.currentTimeMillis() - t0) / 1000.0));
        return out;
    }

    private List<String> translateBatch(List<String> batch, String targetLang, LlmConfig cfg,
                                        java.util.Map<String, String> glossary, String stylePrompt) {
        return translateBatch(batch, targetLang, cfg, glossary, stylePrompt, null, null).lines();
    }

    /** 单批产出：译文行（与输入等长、未译处为原文）+ 未译行的批内下标。 */
    public record BatchOutcome(List<String> lines, List<Integer> missing) {
    }

    /**
     * 翻译单个批次，可携带前文上下文（Agent 模式 ⑧ 的滚动窗口用）。
     * <p>公开单批接口是为了让调用方自己控制分批与并发策略——Agent 模式需要
     * 「组内串行带上下文、组间并发」，而本类的 translate() 是全批次并发，两者并存。
     * <p>返回 {@link BatchOutcome}：调用方能知道哪些行最终没译成（保留了原文），
     * 从而把「部分未译」如实标到任务上，而不是静默混在译文里让用户自己发现。
     *
     * @param priorSource 前文原文（只读上下文，不参与 index 对齐、不要求返回译文）
     * @param priorTarget 前文译文（可为 null；组内串行时才有）
     */
    public BatchOutcome translateBatchWithContext(List<String> batch, String targetLang, LlmConfig cfg,
                                                  java.util.Map<String, String> glossary, String stylePrompt,
                                                  List<String> priorSource, List<String> priorTarget) {
        if (!StringUtils.hasText(cfg.apiKey())) {
            throw new BizException("未配置翻译模型 API Key，请在设置里填写或用环境变量");
        }
        return translateBatch(batch, targetLang, cfg,
                glossary == null ? java.util.Map.of() : glossary,
                StringUtils.hasText(stylePrompt) ? stylePrompt.trim() : null,
                priorSource, priorTarget);
    }

    private BatchOutcome translateBatch(List<String> batch, String targetLang, LlmConfig cfg,
                                        java.util.Map<String, String> glossary, String stylePrompt,
                                        List<String> priorSource, List<String> priorTarget) {
        int n = batch.size();
        String[] result = batch.toArray(new String[0]); // 默认=原文
        boolean[] filled = new boolean[n];

        // 最多三轮请求，每轮只发「还没拿到译文」的行（首轮=全批）。
        // 旧策略是「部分成功就接受、不为几行重发整批」——结果模型漏行时那些行直接保留原文
        // （实测 deepseek-v4-pro 一次漏 17/40，成品里塌出一大块没翻译的文本）。
        // 现在漏行只重发缺的小子集：请求便宜，输出小也天然规避截断。
        for (int attempt = 1; attempt <= 3 && !allFilled(filled); attempt++) {
            List<Integer> todo = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (!filled[i]) {
                    todo.add(i);
                }
            }
            List<String> sub = new ArrayList<>(todo.size());
            for (int g : todo) {
                sub.add(batch.get(g));
            }
            try {
                String sys = buildSystemPrompt(targetLang, sub, glossary, stylePrompt, priorSource, priorTarget);
                String content = chat(sys, buildUserPrompt(sub), cfg);
                applyTranslations(content, result, filled, todo);
            } catch (Exception e) {
                log.warn("批量翻译失败(第{}次尝试, {} 行): {}", attempt, sub.size(), e.getMessage());
                // 网络/限流类失败立刻原样重试大概率还挂，等一拍再试；被取消就立刻收尾
                if (attempt < 3 && !sleepQuietly(1500L * attempt)) {
                    break;
                }
            }
        }
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!filled[i]) {
                missing.add(i);
            }
        }
        if (!missing.isEmpty()) {
            log.warn("补翻后仍有 {}/{} 行未译，这些行保留原文", missing.size(), n);
        }
        return new BatchOutcome(new ArrayList<>(List.of(result)), missing);
    }

    private static boolean sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 把模型返回的译文回填到 result（只填尚未填的行）。
     * <p>mapping 把「本次请求内的行号」映射回批内行号——补翻只发缺行子集，
     * 模型看到的 i 是子集内的 0..m-1，不经映射会填错行。
     * 兼容三种返回：①[{"i":行号,"t":"译文"}] ②{"translations":[...]} 同上
     * ③长度与请求行数一致的纯字符串数组（按位置回填，兜底）。
     * <p><b>空白译文不算数</b>：回填空串等于把原文抹掉，比不翻还糟——留给下一轮补翻。
     */
    void applyTranslations(String content, String[] result, boolean[] filled, List<Integer> mapping) {
        if (content == null) return;
        String s = content.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            s = s.substring(start, end + 1);
        }
        JsonNode arr;
        try {
            arr = mapper.readTree(s).path("translations");
        } catch (Exception e) {
            return;
        }
        if (!arr.isArray()) return;

        int m = mapping.size();
        boolean allTextual = true;
        for (JsonNode node : arr) {
            if (!node.isTextual()) { allTextual = false; break; }
        }

        if (allTextual && arr.size() == m) {
            // 纯字符串数组且行数恰好相等：按位置回填
            for (int i = 0; i < m; i++) {
                int g = mapping.get(i);
                String text = arr.get(i).asText();
                if (!filled[g] && StringUtils.hasText(text)) {
                    result[g] = text;
                    filled[g] = true;
                }
            }
            return;
        }
        // 带 index 的对象：按 i 精确回填，漏掉的行不受影响
        for (JsonNode node : arr) {
            int i = node.has("i") ? node.path("i").asInt(-1) : node.path("index").asInt(-1);
            if (i < 0 || i >= m) continue;
            int g = mapping.get(i);
            if (filled[g]) continue;
            JsonNode t = node.has("t") ? node.path("t") : node.path("translation");
            if (t.isMissingNode()) t = node.path("text");
            String text = t.asText("");
            if (!StringUtils.hasText(text)) continue;
            result[g] = text;
            filled[g] = true;
        }
    }

    private boolean allFilled(boolean[] filled) {
        for (boolean b : filled) if (!b) return false;
        return true;
    }

    private String buildSystemPrompt(String targetLang, List<String> batch,
                                     java.util.Map<String, String> glossary, String stylePrompt,
                                     List<String> priorSource, List<String> priorTarget) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是专业的视频字幕翻译。把 lines 数组里每个对象的 text 翻译成").append(targetLang);
        if (StringUtils.hasText(stylePrompt)) {
            // 用户指定风格时，默认的"口语化"让位，避免与用户风格冲突
            sb.append("。要求：忠实原意、避免翻译腔；保留专有名词。")
                    .append("【翻译风格要求，必须遵循】").append(stylePrompt.trim()).append("。");
        } else {
            sb.append("。要求：忠实自然、口语化、避免翻译腔；保留专有名词。");
        }
        // 注入当前批次中出现的术语对照（必须遵守，优先级高于风格）
        if (glossary != null && !glossary.isEmpty()) {
            String joined = String.join("\n", batch);
            StringBuilder terms = new StringBuilder();
            for (java.util.Map.Entry<String, String> e : glossary.entrySet()) {
                if (StringUtils.hasText(e.getKey()) && joined.contains(e.getKey())) {
                    terms.append("「").append(e.getKey()).append("」=>「").append(e.getValue()).append("」；");
                }
            }
            if (terms.length() > 0) {
                sb.append("【术语对照，必须严格按此翻译，不得改写】：").append(terms);
            }
        }
        sb.append("只返回 JSON 对象：{\"translations\": [{\"i\": 行号, \"t\": \"译文\"}]}，")
                .append("其中 i 必须与输入对象的 i 一一对应；必须为每一个输入行各返回恰好一条，")
                .append("不要合并、拆分或遗漏任何行，不要改变 i，不要输出任何额外文字。");
        // 滚动窗口（Agent 模式 ⑧）：前文只读，用于保持指代/称谓/语气连续，不参与 index 对齐
        appendContext(sb, priorSource, priorTarget);
        return sb.toString();
    }

    /** 把前文原文/译文作为只读上下文附在提示词末尾。 */
    private void appendContext(StringBuilder sb, List<String> priorSource, List<String> priorTarget) {
        boolean hasSrc = priorSource != null && !priorSource.isEmpty();
        boolean hasTgt = priorTarget != null && !priorTarget.isEmpty();
        if (!hasSrc && !hasTgt) {
            return;
        }
        sb.append("\n【前文上下文，仅供参考以保持指代、称谓、语气一致，不要翻译它、不要在结果中返回它】\n");
        if (hasSrc) {
            sb.append("前文原文：").append(String.join(" ", priorSource)).append('\n');
        }
        if (hasTgt) {
            sb.append("前文译文：").append(String.join(" ", priorTarget)).append('\n');
        }
    }

    private String buildUserPrompt(List<String> batch) {
        try {
            ArrayNode arr = mapper.createArrayNode();
            for (int i = 0; i < batch.size(); i++) {
                ObjectNode line = mapper.createObjectNode();
                line.put("i", i);
                line.put("text", batch.get(i));
                arr.add(line);
            }
            ObjectNode obj = mapper.createObjectNode();
            obj.set("lines", arr);
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new BizException("构造翻译请求失败: " + e.getMessage());
        }
    }

    private String chat(String system, String user, LlmConfig cfg) {
        ObjectNode req = mapper.createObjectNode();
        req.put("model", cfg.model());
        req.put("temperature", 0.3);
        ObjectNode rf = req.putObject("response_format");
        rf.put("type", "json_object");
        if (cfg.disableThinking()) {
            req.putObject("thinking").put("type", "disabled");
        }
        ArrayNode messages = req.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);

        String resp;
        long t0 = System.currentTimeMillis();
        try {
            resp = client.post()
                    // 尾斜杠必须剥掉：FastAPI 系代理把 //chat/completions 当另一条路径直接 404
                    .uri(cfg.baseUrl().replaceAll("/+$", "") + "/chat/completions")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(req))
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new BizException("LLM 请求失败: " + e.getMessage());
        }
        try {
            JsonNode root = mapper.readTree(resp);
            // 每批耗时 + token 用量（total 远大于 prompt+completion 说明思考在烧 token）
            JsonNode usage = root.path("usage");
            long promptTk = usage.path("prompt_tokens").asLong(0);
            long completionTk = usage.path("completion_tokens").asLong(0);
            long totalTk = usage.path("total_tokens").asLong(0);
            long hiddenTk = Math.max(0, totalTk - promptTk - completionTk);
            log.info("LLM 批次: {}ms, 模型 {}, tokens prompt={} completion={} total={}{}",
                    System.currentTimeMillis() - t0, cfg.model(), promptTk, completionTk, totalTk,
                    hiddenTk > 0 ? "（思考≈" + hiddenTk + "）" : "");
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            throw new BizException("解析 LLM 响应失败: " + e.getMessage());
        }
    }
}
