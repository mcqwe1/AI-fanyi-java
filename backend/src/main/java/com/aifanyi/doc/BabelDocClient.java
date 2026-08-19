package com.aifanyi.doc;

import com.aifanyi.common.BizException;
import com.aifanyi.config.AifanyiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aifanyi.llm.LlmConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 调本机 ai-service 的 BabelDOC PDF 版式保持翻译（POST /pdf/translate + 轮询）。
 * <p>只支持 OpenAI 兼容协议的模型服务——babeldoc 内部用 openai SDK 直连；
 * claude / 传统机翻协议由调用方走内置 pdfbox 回填引擎。
 */
@Slf4j
@Component
public class BabelDocClient {

    /** 与其他 java→uvicorn 客户端同理：必须锁 HTTP/1.1，h2c 升级头会让 uvicorn 丢请求体。 */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final ObjectMapper mapper;
    private final String baseUrl;

    public BabelDocClient(AifanyiProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        this.baseUrl = props.getAsr().getLocal().getBaseUrl().replaceAll("/+$", "");
    }

    /** 任务状态快照（progress 0~100）。 */
    public record JobStatus(String status, String stage, double progress,
                            String monoPath, String dualPath, String error) {
        public boolean running() {
            return "RUNNING".equals(status);
        }

        public boolean success() {
            return "SUCCESS".equals(status);
        }
    }

    /** 提交任务，返回 job_id。失败抛 BizException（调用方据此回退内置引擎）。 */
    public String start(Path source, Path outDir, LlmConfig cfg, String langIn, String langOut) {
        ObjectNode req = mapper.createObjectNode();
        req.put("source_path", source.toString());
        req.put("out_dir", outDir.toString());
        req.put("base_url", cfg.baseUrl());
        req.put("api_key", cfg.apiKey());
        req.put("model", cfg.model());
        req.put("lang_in", langIn);
        req.put("lang_out", langOut);
        // babeldoc 的 qps 同时决定其内部工作线程数；与项目并发配置对齐
        req.put("qps", Math.max(4, cfg.concurrency()));
        req.put("no_dual", true);
        JsonNode resp = call("POST", "/pdf/translate", req, Duration.ofSeconds(30));
        String jobId = resp.path("job_id").asText("");
        if (jobId.isEmpty()) {
            throw new BizException("BabelDOC 启动响应缺少 job_id");
        }
        return jobId;
    }

    public JobStatus poll(String jobId) {
        JsonNode r = call("GET", "/pdf/job/" + jobId, null, Duration.ofSeconds(15));
        return new JobStatus(r.path("status").asText(""), r.path("stage").asText(""),
                r.path("progress").asDouble(0),
                r.path("mono_path").isNull() ? null : r.path("mono_path").asText(null),
                r.path("dual_path").isNull() ? null : r.path("dual_path").asText(null),
                r.path("error").isNull() ? null : r.path("error").asText(null));
    }

    /** 取消（任务被用户删除时）：尽力而为，失败只记日志。 */
    public void cancel(String jobId) {
        try {
            call("DELETE", "/pdf/job/" + jobId, null, Duration.ofSeconds(10));
        } catch (Exception e) {
            log.warn("取消 BabelDOC 任务 {} 失败: {}", jobId, e.getMessage());
        }
    }

    private JsonNode call(String method, String path, ObjectNode body, Duration timeout) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json");
            b.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),
                            StandardCharsets.UTF_8));
            HttpResponse<String> resp = http.send(b.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new BizException("BabelDOC 服务 " + path + " 返回 " + resp.statusCode()
                        + ": " + detail(resp.body()));
            }
            return mapper.readTree(resp.body());
        } catch (BizException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("BabelDOC 请求被中断");
        } catch (Exception e) {
            throw new BizException("BabelDOC 服务不可达: " + e.getMessage());
        }
    }

    /** FastAPI 错误体是 {"detail": "..."}，抽出来给用户可读的信息。 */
    private String detail(String body) {
        try {
            String d = mapper.readTree(body).path("detail").asText("");
            return d.isEmpty() ? body : d;
        } catch (Exception e) {
            return body;
        }
    }
}
