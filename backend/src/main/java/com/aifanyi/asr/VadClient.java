package com.aifanyi.asr;

import com.aifanyi.common.BizException;
import com.aifanyi.config.AifanyiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 调本机 ai-service 的 Silero VAD（POST /vad），返回全音频的语音区间（毫秒）。
 * 供字幕起点吸附用；ai-service 不可用时由调用方降级跳过。
 */
@Component
public class VadClient {

    private final AifanyiProperties.Asr.Local cfg;
    private final ObjectMapper mapper;
    private final RestClient client;

    public VadClient(AifanyiProperties props, ObjectMapper mapper) {
        this.cfg = props.getAsr().getLocal();
        this.mapper = mapper;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        // 长音频解码+VAD 可能要几分钟（CPU），给足余量
        rf.setReadTimeout(Duration.ofMinutes(6));
        this.client = RestClient.builder().requestFactory(rf).build();
    }

    /** 返回 [startMs, endMs] 语音区间列表（升序）。 */
    public List<long[]> speechRegionsMs(Path audio) {
        String url = cfg.getBaseUrl().replaceAll("/+$", "") + "/vad";
        ObjectNode req = mapper.createObjectNode();
        req.put("audio_path", audio.toString());
        String resp;
        try {
            resp = client.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(req))
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new BizException("VAD 请求失败: " + e.getMessage());
        }
        // 2xx 空体（某些代理返回 200/204 无内容）：按“无语音区间”降级，别让 readTree(null) 抛误导性异常
        if (resp == null || resp.isBlank()) {
            return List.of();
        }
        try {
            JsonNode regions = mapper.readTree(resp).path("regions");
            List<long[]> out = new ArrayList<>();
            if (regions.isArray()) {
                for (JsonNode r : regions) {
                    out.add(new long[]{
                            Math.round(r.path("start").asDouble() * 1000),
                            Math.round(r.path("end").asDouble() * 1000)});
                }
            }
            return out;
        } catch (Exception e) {
            throw new BizException("解析 VAD 响应失败: " + e.getMessage());
        }
    }
}
