package com.aifanyi.asr;

import com.aifanyi.common.BizException;
import com.aifanyi.config.AifanyiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 本地 faster-whisper（GPU 优先）。同机的 ai-service（FastAPI）实际跑模型，
 * 本 provider 通过 /transcribe 直接传音频绝对路径（免上传），按 ctx.model 选尺寸。
 * 前端值 local-base / local-small / local-medium / local-large-v3 → ctx.model 即 size。
 */
@Slf4j
@Component
public class LocalWhisperProvider implements AsrProvider {

    private final AifanyiProperties.Asr.Local cfg;
    private final ObjectMapper mapper;
    private final RestClient client;
    private final AiServiceLauncher launcher;
    /** 转写进度轮询线程（守护线程，读 ai-service 写的进度文件） */
    private final ScheduledExecutorService progressPoller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "asr-progress");
        t.setDaemon(true);
        return t;
    });

    public LocalWhisperProvider(AifanyiProperties props, ObjectMapper mapper, AiServiceLauncher launcher) {
        this.cfg = props.getAsr().getLocal();
        this.mapper = mapper;
        this.launcher = launcher;
        // 转写可能耗时数分钟（CPU 跑 large-v3），放宽读超时
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofMinutes(30));
        this.client = RestClient.builder().requestFactory(rf).build();
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public List<Segment> transcribe(Path audio, String language, AsrContext ctx) {
        // 服务没起就自动拉起（组件未安装/启动失败会抛出带指引的错误）
        launcher.ensureRunning(true);
        String size = (ctx != null && StringUtils.hasText(ctx.model())) ? ctx.model() : "large-v3";

        // 进度通道：ai-service 边转写边把 0~1 写进该文件，这里定时轮询回报（同机文件传递，零改协议成本）
        Path progressFile = audio.toAbsolutePath().getParent().resolve("asr_progress.txt");
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("audio_path", audio.toAbsolutePath().toString());
        req.put("language", StringUtils.hasText(language) ? language : "auto");
        req.put("size", size);
        req.put("progress_path", progressFile.toString());

        ScheduledFuture<?> poll = null;
        if (ctx != null && ctx.progress() != null) {
            try {
                Files.deleteIfExists(progressFile);
            } catch (Exception ignored) {
            }
            final AsrContext fctx = ctx;
            poll = progressPoller.scheduleWithFixedDelay(() -> {
                try {
                    String s = Files.readString(progressFile).trim();
                    fctx.reportProgress(Double.parseDouble(s));
                } catch (Exception ignored) {
                    // 文件未生成/写一半解析失败都正常，下个周期再读
                }
            }, 2, 2, TimeUnit.SECONDS);
        }

        String json;
        try {
            json = client.post()
                    .uri(cfg.getBaseUrl() + "/transcribe")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .body(String.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // ai-service 返回了错误响应：透传 FastAPI 的 detail（如模型下载失败的真实原因与指引）
            throw new BizException("本地 Whisper 转写失败: " + extractDetail(e));
        } catch (Exception e) {
            throw new BizException("本地 Whisper 转写失败：无法连接本地识别服务。"
                    + "请先双击软件目录里的 run-ai-service.bat 启动"
                    + "（首次使用先运行 setup-ai-service.bat 安装），或改用 Groq 云端识别。"
                    + "原始错误: " + e.getMessage());
        } finally {
            if (poll != null) {
                poll.cancel(false);
            }
            try {
                Files.deleteIfExists(progressFile);
            } catch (Exception ignored) {
            }
        }
        if (ctx != null) {
            ctx.reportProgress(1.0);
        }

        return parse(json);
    }

    /** 从 FastAPI 错误响应体里抽 detail 字段；解析不了就退回状态文本。 */
    private String extractDetail(org.springframework.web.client.RestClientResponseException e) {
        try {
            JsonNode root = mapper.readTree(e.getResponseBodyAsString());
            String detail = root.path("detail").asText("");
            if (!detail.isBlank()) {
                return detail;
            }
        } catch (Exception ignore) {
            // 响应体不是 JSON，用状态文本兜底
        }
        return e.getStatusText() + "（" + e.getStatusCode().value() + "）";
    }

    private List<Segment> parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode segs = root.path("segments");
            List<Segment> result = new ArrayList<>();
            if (segs.isArray()) {
                for (JsonNode s : segs) {
                    String text = s.path("text").asText("").trim();
                    if (text.isEmpty()) {
                        continue;
                    }
                    // ai-service 已用 word 收紧 start/end（秒）
                    long start = Math.round(s.path("start").asDouble() * 1000);
                    long end = Math.round(s.path("end").asDouble() * 1000);
                    result.add(new Segment(start, end, text));
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("本地 Whisper: device={} compute={} lang={} segs={}",
                        root.path("device").asText(""), root.path("compute_type").asText(""),
                        root.path("language").asText(""), result.size());
            }
            return result;
        } catch (Exception e) {
            throw new BizException("解析本地 Whisper 响应失败: " + e.getMessage());
        }
    }
}
