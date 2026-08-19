package com.aifanyi.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Agent 包专用 HTTP 客户端（harness 约束层）。
 * <p><b>为什么不用项目里其他地方的 RestClient</b>：它们基于
 * {@code SimpleClientHttpRequestFactory}，底层是阻塞的 {@code Socket.read()}——
 * {@code Future.cancel(true)} 只是给线程打中断标记，<b>解不开这个阻塞</b>。
 * 于是「墙钟 30s」形同虚设：取消一个子 Agent 后它的线程仍会占着直到读超时
 * （GeminiClient 那边是 120s），小池必然饿死。
 * <p>{@link HttpClient} 支持逐请求 {@code timeout()} 且响应线程中断，是让 30 秒
 * 墙钟成为事实的唯一途径。包外一律不动，保持现有代码零风险。
 */
@Slf4j
@Component
public class AgentHttp {

    /** 单次请求超时上限，防止剩余预算很大时挂太久 */
    private static final long MAX_TIMEOUT_MS = 120_000;
    /** 低于此值不值得发请求（握手+首字节都不够） */
    public static final long MIN_TIMEOUT_MS = 1_500;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            // 必须锁 HTTP/1.1：默认 HTTP_2 对 http:// 目标会发 h2c 升级头（Upgrade: h2c），
            // 本机 uvicorn（/ocr、/embed）不支持该升级且会丢请求体——FastAPI 看到空 body
            // 直接 422，还在日志刷 "Invalid HTTP request received"。实测踩坑：图片翻译
            // 全链路 422，且 /embed 早已因此静默降级（它失败不报错，一直没暴露）。
            // https 目标走 ALPN 不受影响，锁 1.1 只是放弃了用不上的多路复用。
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** HTTP 调用结果：状态码 + 响应体。非 2xx 也正常返回，由调用方决定降级方式。 */
    public record Result(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * 发一次 JSON POST。
     *
     * @param timeoutMs 本次超时（调用方传 min(预算剩余, 业务上限)）
     * @throws InterruptedException 线程被取消时抛出——这正是我们要的可中断性
     */
    public Result postJson(String url, Map<String, String> headers, String body, long timeoutMs)
            throws InterruptedException {
        long t = Math.max(MIN_TIMEOUT_MS, Math.min(timeoutMs, MAX_TIMEOUT_MS));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(t))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (headers != null) {
            headers.forEach(b::header);
        }
        try {
            HttpResponse<String> resp = http.send(b.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Result(resp.statusCode(), resp.body());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        } catch (Exception e) {
            // 网络层失败统一表达为 0 状态码，调用方按降级处理，绝不抛业务异常
            log.debug("Agent HTTP 失败 {}: {}", url, e.toString());
            return new Result(0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** PATCH JSON，语义同 postJson（LangSmith 收尾根 run 用）。 */
    public Result patchJson(String url, Map<String, String> headers, String body, long timeoutMs)
            throws InterruptedException {
        long t = Math.max(MIN_TIMEOUT_MS, Math.min(timeoutMs, MAX_TIMEOUT_MS));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(t))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (headers != null) {
            headers.forEach(b::header);
        }
        try {
            HttpResponse<String> resp = http.send(b.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Result(resp.statusCode(), resp.body());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        } catch (Exception e) {
            log.debug("Agent HTTP 失败 {}: {}", url, e.toString());
            return new Result(0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** GET，同上语义。 */
    public Result get(String url, Map<String, String> headers, long timeoutMs)
            throws InterruptedException {
        long t = Math.max(MIN_TIMEOUT_MS, Math.min(timeoutMs, MAX_TIMEOUT_MS));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(t))
                .GET();
        if (headers != null) {
            headers.forEach(b::header);
        }
        try {
            HttpResponse<String> resp = http.send(b.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Result(resp.statusCode(), resp.body());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        } catch (Exception e) {
            log.debug("Agent HTTP 失败 {}: {}", url, e.toString());
            return new Result(0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 剥掉 base URL 尾部斜杠再拼路径：FastAPI 系代理把 //chat/completions 当另一条路径直接 404。 */
    public static String join(String baseUrl, String path) {
        return baseUrl.replaceAll("/+$", "") + path;
    }
}
