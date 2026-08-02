package com.aifanyi.agent.trace;

import com.aifanyi.agent.AgentHttp;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LangSmith 云端观测上报（可选调试功能，harness 评估与观测层）。
 *
 * <p><b>定位</b>：给开发者/高级用户做「比 agent_trace 更细」的调试——完整提示词、
 * 完整模型返回、父子调用树、逐段耗时，在 LangSmith 网页端可视化。
 * agent_trace 落库的是摘要（digest），这里上报的是全文。
 *
 * <p><b>三条铁律</b>：
 * <ol>
 *   <li>没填 Key 就完全不工作，一行代码都不多跑（隐私默认关闭——上报内容含用户
 *       视频的完整台词，发到外部云端必须是用户显式选择）；</li>
 *   <li>绝不阻塞主链：所有上报进内存队列由单守护线程慢慢发，队列满了直接丢，
 *       LangSmith 挂了最多损失观测数据，翻译任务不受任何影响；</li>
 *   <li>绝不抛异常。</li>
 * </ol>
 *
 * <p>Wire 格式（LangSmith REST）：{@code POST /runs} 建一条 run（可一次带全
 * outputs/end_time），{@code PATCH /runs/{id}} 收尾根 run；鉴权头 {@code x-api-key}；
 * 树形结构靠 {@code trace_id}（根 run 的 id）+ {@code parent_run_id} +
 * {@code dotted_order}（时间戳+id 逐层用 . 拼接）。
 */
@Slf4j
@Component
public class LangSmithExporter {

    private static final String DEFAULT_ENDPOINT = "https://api.smith.langchain.com";
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSSSSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ISO_INSTANT;
    /** 上报队列上限：满了丢弃（观测数据可丢，主链不可慢） */
    private static final int QUEUE_CAP = 2000;
    /** 单条上报的字段截断（完整台词很长，LangSmith 单请求别超限） */
    private static final int MAX_FIELD = 60_000;

    private final ObjectMapper mapper;
    private final AgentHttp http;
    private final LinkedBlockingQueue<Req> queue = new LinkedBlockingQueue<>(QUEUE_CAP);
    private final Thread sender;
    private final AtomicBoolean warned = new AtomicBoolean();
    private volatile boolean running = true;

    /** 一次待发请求。 */
    private record Req(String method, String url, String apiKey, String body) {
    }

    /** 一个任务的上报上下文（根 run 已建，子 run 挂在它下面）。 */
    public record Ctx(String apiKey, String project, String traceId, String rootDotted) {
    }

    public LangSmithExporter(ObjectMapper mapper, AgentHttp http) {
        this.mapper = mapper;
        this.http = http;
        this.sender = new Thread(this::drain, "langsmith-sender");
        this.sender.setDaemon(true);
        this.sender.start();
    }

    @PreDestroy
    void shutdown() {
        running = false;
        sender.interrupt();
    }

    // ─────────────────────────── 对外 API ───────────────────────────

    /**
     * 任务开始：建根 run。返回 null 表示未启用（没配 Key），调用方把 null 一路传下去即可，
     * 后续所有方法对 null ctx 都是空操作。
     */
    public Ctx begin(String apiKey, String project, String name, Map<String, Object> inputs) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String dotted = TS.format(now) + id;
        Ctx ctx = new Ctx(apiKey.trim(),
                project == null || project.isBlank() ? "aifanyi" : project.trim(),
                id, dotted);
        ObjectNode run = baseRun(ctx, id, null, dotted, name, "chain", now);
        run.set("inputs", mapper.valueToTree(inputs == null ? Map.of() : inputs));
        enqueue("POST", "/runs", ctx.apiKey(), run);
        return ctx;
    }

    /** 任务收尾：PATCH 根 run 的 outputs / error / end_time。 */
    public void end(Ctx ctx, Map<String, Object> outputs, String error) {
        if (ctx == null) {
            return;
        }
        ObjectNode patch = mapper.createObjectNode();
        patch.put("end_time", ISO.format(Instant.now()));
        patch.set("outputs", mapper.valueToTree(outputs == null ? Map.of() : outputs));
        if (error != null && !error.isBlank()) {
            patch.put("error", clamp(error));
        }
        enqueue("PATCH", "/runs/" + ctx.traceId(), ctx.apiKey(), patch);
    }

    /**
     * 上报一次 LLM 调用（完整提示词 + 完整返回）。在调用完成后一次性 POST，
     * 不做两段式——观测数据没有实时性要求，少一半请求。
     *
     * @param elapsedMs 调用耗时；start_time 由 now-elapsed 反推
     */
    public void llm(Ctx ctx, String name, String model, String system, String user,
                    String output, String stopReason, long elapsedMs,
                    long promptTokens, long completionTokens) {
        if (ctx == null) {
            return;
        }
        String id = UUID.randomUUID().toString();
        Instant start = Instant.now().minusMillis(Math.max(0, elapsedMs));
        String dotted = ctx.rootDotted() + "." + TS.format(start) + id;
        ObjectNode run = baseRun(ctx, id, ctx.traceId(), dotted, name, "llm", start);
        ObjectNode in = run.putObject("inputs");
        in.put("model", model);
        in.put("system", clamp(system));
        in.put("user", clamp(user));
        ObjectNode out = run.putObject("outputs");
        out.put("content", clamp(output));
        out.put("stop_reason", stopReason);
        run.put("end_time", ISO.format(start.plusMillis(Math.max(0, elapsedMs))));
        ObjectNode extra = run.putObject("extra");
        ObjectNode meta = extra.putObject("metadata");
        meta.put("prompt_tokens", promptTokens);
        meta.put("completion_tokens", completionTokens);
        enqueue("POST", "/runs", ctx.apiKey(), run);
    }

    /**
     * 上报一个非 LLM 步骤（搜索命中、仲裁后的术语表、落库分档、注入翻译的清单……）。
     * <p>由各节点经 TraceRecorder.stepFull <b>显式调用</b>，字段名用人话、内容给全。
     * 曾经的做法是把 agent_trace 的摘要串自动镜像成 tool run，结果云端全是
     * 「extract:5778字」这种记账缩写，没人看得懂，已废弃。
     */
    public void step(Ctx ctx, String name, Map<String, Object> inputs, Map<String, Object> outputs,
                     long elapsedMs, boolean degraded) {
        if (ctx == null) {
            return;
        }
        String id = UUID.randomUUID().toString();
        Instant start = Instant.now().minusMillis(Math.max(0, elapsedMs));
        String dotted = ctx.rootDotted() + "." + TS.format(start) + id;
        ObjectNode run = baseRun(ctx, id, ctx.traceId(), dotted, name, "tool", start);
        putClamped(run.putObject("inputs"), inputs);
        ObjectNode out = run.putObject("outputs");
        putClamped(out, outputs);
        if (degraded) {
            out.put("降级", true);
        }
        run.put("end_time", ISO.format(start.plusMillis(Math.max(0, elapsedMs))));
        enqueue("POST", "/runs", ctx.apiKey(), run);
    }

    /** 字符串值统一截断（术语清单/搜索结果可能很长），其余类型原样序列化。 */
    private void putClamped(ObjectNode node, Map<String, Object> fields) {
        if (fields == null) {
            return;
        }
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (e.getValue() instanceof String s) {
                node.put(e.getKey(), clamp(s));
            } else {
                node.set(e.getKey(), mapper.valueToTree(e.getValue()));
            }
        }
    }

    /**
     * 连通性测试（同步，给设置页的「测试连接」按钮用）：
     * 发一条最小 run，返回人话结果。这是唯一一个同步调用 LangSmith 的方法。
     */
    public String testConnection(String apiKey, String project) {
        if (apiKey == null || apiKey.isBlank()) {
            return "请先填写 API Key";
        }
        try {
            String id = UUID.randomUUID().toString();
            Instant now = Instant.now();
            Ctx tmp = new Ctx(apiKey.trim(),
                    project == null || project.isBlank() ? "aifanyi" : project.trim(),
                    id, TS.format(now) + id);
            ObjectNode run = baseRun(tmp, id, null, tmp.rootDotted(), "aifanyi-连接测试", "chain", now);
            run.putObject("inputs").put("ping", "pong");
            run.put("end_time", ISO.format(now));
            run.putObject("outputs").put("ok", true);
            AgentHttp.Result r = http.postJson(DEFAULT_ENDPOINT + "/runs",
                    Map.of("x-api-key", tmp.apiKey()), mapper.writeValueAsString(run), 10_000);
            if (r.ok()) {
                return "连接成功！到 LangSmith 网页端项目「" + tmp.project() + "」里应能看到一条 aifanyi-连接测试";
            }
            if (r.status() == 401 || r.status() == 403) {
                return "API Key 无效（HTTP " + r.status() + "）";
            }
            return "连接失败 HTTP " + r.status() + "：" + clampTo(r.body(), 160);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "已取消";
        } catch (Exception e) {
            return "连接失败：" + e.getMessage();
        }
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private ObjectNode baseRun(Ctx ctx, String id, String parentTraceId, String dotted,
                               String name, String runType, Instant start) {
        ObjectNode run = mapper.createObjectNode();
        run.put("id", id);
        run.put("trace_id", ctx.traceId());
        run.put("dotted_order", dotted);
        if (parentTraceId != null) {
            run.put("parent_run_id", parentTraceId);
        }
        run.put("name", name);
        run.put("run_type", runType);
        run.put("start_time", ISO.format(start));
        run.put("session_name", ctx.project());
        return run;
    }

    private void enqueue(String method, String path, String apiKey, ObjectNode body) {
        try {
            String json = mapper.writeValueAsString(body);
            if (!queue.offer(new Req(method, DEFAULT_ENDPOINT + path, apiKey, json))) {
                // 队列满：丢弃并只警告一次（观测数据可丢，绝不能反压主链）
                if (warned.compareAndSet(false, true)) {
                    log.warn("LangSmith 上报队列已满，开始丢弃观测数据（不影响翻译任务）");
                }
            }
        } catch (Exception ignored) {
            // 序列化失败也吞掉
        }
    }

    private void drain() {
        while (running) {
            try {
                Req r = queue.take();
                AgentHttp.Result res = "PATCH".equals(r.method())
                        ? http.patchJson(r.url(), Map.of("x-api-key", r.apiKey()), r.body(), 8_000)
                        : http.postJson(r.url(), Map.of("x-api-key", r.apiKey()), r.body(), 8_000);
                if (!res.ok() && warned.compareAndSet(false, true)) {
                    log.warn("LangSmith 上报失败 HTTP {}（后续失败不再重复提示）: {}",
                            res.status(), clampTo(res.body(), 200));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (warned.compareAndSet(false, true)) {
                    log.warn("LangSmith 上报异常（后续不再重复提示）: {}", e.toString());
                }
            }
        }
    }

    private static String clamp(String s) {
        return clampTo(s, MAX_FIELD);
    }

    private static String clampTo(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…(截断)";
    }
}
