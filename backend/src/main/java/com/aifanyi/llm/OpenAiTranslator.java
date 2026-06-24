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
        if (!StringUtils.hasText(cfg.apiKey())) {
            throw new BizException("未配置翻译模型 API Key，请在设置里填写或用环境变量");
        }
        int batchSize = Math.max(1, cfg.batchSize());

        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < sources.size(); i += batchSize) {
            batches.add(sources.subList(i, Math.min(i + batchSize, sources.size())));
        }

        List<Future<List<String>>> futures = new ArrayList<>(batches.size());
        for (List<String> batch : batches) {
            futures.add(pool.submit(() -> translateBatch(batch, targetLang, cfg)));
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

    private List<String> translateBatch(List<String> batch, String targetLang, LlmConfig cfg) {
        try {
            String content = chat(buildSystemPrompt(targetLang), buildUserPrompt(batch), cfg);
            List<String> parsed = parseTranslations(content);
            if (parsed.size() == batch.size()) {
                return parsed;
            }
            log.warn("译文行数({})与原文({})不一致，保留原文", parsed.size(), batch.size());
        } catch (Exception e) {
            log.warn("批量翻译失败: {}", e.getMessage());
        }
        return new ArrayList<>(batch);
    }

    private String buildSystemPrompt(String targetLang) {
        return "你是专业的视频字幕翻译。把 lines 数组里的每一行翻译成" + targetLang
                + "。要求：忠实自然、口语化、避免翻译腔；保留专有名词。"
                + "只返回 JSON 对象：{\"translations\": [...]}，"
                + "translations 数组的长度和顺序必须与输入 lines 完全一致，不要合并或拆分行，不要输出任何额外文字。";
    }

    private String buildUserPrompt(List<String> batch) {
        try {
            ArrayNode arr = mapper.createArrayNode();
            batch.forEach(arr::add);
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
                    .uri(cfg.baseUrl() + "/chat/completions")
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

    private List<String> parseTranslations(String content) {
        List<String> result = new ArrayList<>();
        if (content == null) return result;
        String s = content.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            s = s.substring(start, end + 1);
        }
        try {
            JsonNode node = mapper.readTree(s);
            JsonNode arr = node.path("translations");
            if (arr.isArray()) {
                arr.forEach(n -> result.add(n.asText()));
            }
        } catch (Exception ignore) {
        }
        return result;
    }
}
