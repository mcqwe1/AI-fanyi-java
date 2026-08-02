package com.aifanyi.agent.vector;

import com.aifanyi.agent.AgentHttp;
import com.aifanyi.asr.AiServiceLauncher;
import com.aifanyi.config.AifanyiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 调本机 ai-service 的句向量接口（POST /embed）。
 *
 * <p><b>绝不抛异常</b>：任何失败返回空列表，调用方退化为精确匹配。
 * 向量只服务于「发现近义术语」这一个提示性功能，主链的术语注入走精确字符串匹配，
 * 完全不依赖它——所以它挂掉只是少一个提示，不该让任何任务失败。
 *
 * <p>用 {@link AgentHttp}（java.net.http）而非项目其他地方的 RestClient：
 * 向量化可能发生在子 Agent 的可取消上下文里，需要逐请求超时且响应中断。
 */
@Slf4j
@Component
public class EmbeddingClient {

    /** 与 ai-service 的 EMBED_DIM 对齐（paraphrase-multilingual-MiniLM-L12-v2）。 */
    public static final int DIM = 384;
    /** ai-service 侧单次上限 512，这里留余量分批 */
    private static final int BATCH = 256;
    /** 单批超时：CPU 上 256 条约几百毫秒，给足余量但不至于拖垮调用方 */
    private static final long TIMEOUT_MS = 30_000;

    private final AifanyiProperties.Asr.Local cfg;
    private final ObjectMapper mapper;
    private final AgentHttp http;
    private final AiServiceLauncher launcher;

    public EmbeddingClient(AifanyiProperties props, ObjectMapper mapper, AgentHttp http,
                           AiServiceLauncher launcher) {
        this.cfg = props.getAsr().getLocal();
        this.mapper = mapper;
        this.http = http;
        this.launcher = launcher;
    }

    /**
     * 批量向量化。
     *
     * @return 与入参等长的向量列表；<b>任何失败返回空列表</b>（调用方退化为精确匹配）。
     *         返回非空时保证 size 与 texts 一致，可按下标对齐。
     */
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        // 尽力拉起（装了本地组件就顺带享受近义发现）；拉不起来直接降级，不报错
        if (!launcher.ensureRunning(false)) {
            log.debug("ai-service 不可用，跳过向量化（术语功能退化为精确匹配）");
            return List.of();
        }
        String url = AgentHttp.join(cfg.getBaseUrl(), "/embed");
        List<float[]> out = new java.util.ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += BATCH) {
            List<String> batch = texts.subList(from, Math.min(from + BATCH, texts.size()));
            List<float[]> got = embedBatch(url, batch);
            if (got.size() != batch.size()) {
                // 对不齐就整体放弃：错位的向量比没有向量危险得多
                log.warn("向量化返回条数不符（期望 {} 实得 {}），本次放弃向量功能",
                        batch.size(), got.size());
                return List.of();
            }
            out.addAll(got);
        }
        return out;
    }

    private List<float[]> embedBatch(String url, List<String> batch) {
        try {
            ObjectNode req = mapper.createObjectNode();
            ArrayNode arr = req.putArray("texts");
            batch.forEach(arr::add);
            AgentHttp.Result r = http.postJson(url, Map.of(),
                    mapper.writeValueAsString(req), TIMEOUT_MS);
            if (!r.ok()) {
                log.debug("/embed 返回 {}: {}", r.status(), brief(r.body()));
                return List.of();
            }
            JsonNode vectors = mapper.readTree(r.body()).path("vectors");
            if (!vectors.isArray()) {
                return List.of();
            }
            List<float[]> out = new java.util.ArrayList<>(vectors.size());
            for (JsonNode v : vectors) {
                if (!v.isArray() || v.size() == 0) {
                    return List.of();
                }
                float[] f = new float[v.size()];
                for (int i = 0; i < v.size(); i++) {
                    f[i] = (float) v.get(i).asDouble();
                }
                out.add(f);
            }
            return out;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.debug("/embed 调用失败: {}", e.toString());
            return List.of();
        }
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
