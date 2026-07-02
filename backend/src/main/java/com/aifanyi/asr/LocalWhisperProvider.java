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

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public LocalWhisperProvider(AifanyiProperties props, ObjectMapper mapper) {
        this.cfg = props.getAsr().getLocal();
        this.mapper = mapper;
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
        String size = (ctx != null && StringUtils.hasText(ctx.model())) ? ctx.model() : "large-v3";

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("audio_path", audio.toAbsolutePath().toString());
        req.put("language", StringUtils.hasText(language) ? language : "auto");
        req.put("size", size);

        String json;
        try {
            json = client.post()
                    .uri(cfg.getBaseUrl() + "/transcribe")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new BizException("本地 Whisper 转写失败（ai-service 是否已启动？）: " + e.getMessage());
        }

        return parse(json);
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
