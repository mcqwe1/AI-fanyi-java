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
    public List<String> translate(List<String> sources, String targetLang, LlmConfig cfg) {
        return translate(sources, targetLang, cfg, java.util.Map.of());
    }

    @Override
    public List<String> translate(List<String> sources, String targetLang, LlmConfig cfg,
                                  java.util.Map<String, String> glossary) {
        if (!StringUtils.hasText(cfg.apiKey())) {
            throw new BizException("未配置翻译模型 API Key，请在设置里填写或用环境变量");
        }
        java.util.Map<String, String> gloss = glossary == null ? java.util.Map.of() : glossary;
        int batchSize = Math.max(1, cfg.batchSize());

        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < sources.size(); i += batchSize) {
            batches.add(sources.subList(i, Math.min(i + batchSize, sources.size())));
        }

        List<Future<List<String>>> futures = new ArrayList<>(batches.size());
        for (List<String> batch : batches) {
            futures.add(pool.submit(() -> translateBatch(batch, targetLang, cfg, gloss)));
        }

        List<String> out = new ArrayList<>(sources.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                out.addAll(futures.get(i).get());
            } catch (Exception e) {
                log.warn("第{}批翻译失败，保留原文: {}", i, e.getMessage());
                out.addAll(batches.get(i));
            }
        }
        return out;
    }

    private List<String> translateBatch(List<String> batch, String targetLang, LlmConfig cfg,
                                        java.util.Map<String, String> glossary) {
        int n = batch.size();
        String[] result = batch.toArray(new String[0]); // 默认=原文
        boolean[] filled = new boolean[n];
        String sys = buildSystemPrompt(targetLang, batch, glossary);

        // 最多两次。只在「网络/接口异常」或「整批一条都没拿到」时才重试；
        // 模型只漏个别行（部分成功）就直接接受，剩余行保留原文——避免为几行整批重请求。
        for (int attempt = 1; attempt <= 2; attempt++) {
            int before = countFilled(filled);
            boolean ok = false;
            try {
                String content = chat(sys, buildUserPrompt(batch), cfg);
                applyTranslations(content, result, filled);
                ok = true;
            } catch (Exception e) {
                log.warn("批量翻译失败(第{}次尝试): {}", attempt, e.getMessage());
            }
            if (allFilled(filled)) {
                return new ArrayList<>(List.of(result));
            }
            // 成功响应且这次确实补进了译文（部分成功）→ 接受，不再重试
            if (ok && countFilled(filled) > before) {
                break;
            }
            // 否则（异常 / 整批没解析出译文）→ 进入下一次重试
        }
        int missing = n - countFilled(filled);
        if (missing > 0) {
            log.warn("仍有 {}/{} 行未译，这些行保留原文", missing, n);
        }
        return new ArrayList<>(List.of(result));
    }

    /**
     * 把模型返回的译文按 index 对齐回填到 result（只填尚未填的行）。
     * 兼容三种返回：①[{"i":行号,"t":"译文"}] ②{"translations":[...]} 同上
     * ③长度与原文一致的纯字符串数组（按位置回填，兜底）。
     */
    private void applyTranslations(String content, String[] result, boolean[] filled) {
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

        int n = result.length;
        boolean allTextual = true;
        for (JsonNode node : arr) {
            if (!node.isTextual()) { allTextual = false; break; }
        }

        if (allTextual && arr.size() == n) {
            // 纯字符串数组且行数恰好相等：按位置回填
            for (int i = 0; i < n; i++) {
                if (!filled[i]) {
                    result[i] = arr.get(i).asText();
                    filled[i] = true;
                }
            }
            return;
        }
        // 带 index 的对象：按 i 精确回填，漏掉的行不受影响
        for (JsonNode node : arr) {
            int i = node.has("i") ? node.path("i").asInt(-1) : node.path("index").asInt(-1);
            if (i < 0 || i >= n || filled[i]) continue;
            JsonNode t = node.has("t") ? node.path("t") : node.path("translation");
            if (t.isMissingNode()) t = node.path("text");
            result[i] = t.asText("");
            filled[i] = true;
        }
    }

    private boolean allFilled(boolean[] filled) {
        for (boolean b : filled) if (!b) return false;
        return true;
    }

    private int countFilled(boolean[] filled) {
        int c = 0;
        for (boolean b : filled) if (b) c++;
        return c;
    }

    private String buildSystemPrompt(String targetLang, List<String> batch, java.util.Map<String, String> glossary) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是专业的视频字幕翻译。把 lines 数组里每个对象的 text 翻译成").append(targetLang)
                .append("。要求：忠实自然、口语化、避免翻译腔；保留专有名词。");
        // 注入当前批次中出现的术语对照（必须遵守）
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
        return sb.toString();
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
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            throw new BizException("解析 LLM 响应失败: " + e.getMessage());
        }
    }
}
