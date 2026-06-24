package com.aifanyi.asr;

import com.aifanyi.common.BizException;
import com.aifanyi.config.AifanyiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Groq 云 Whisper（OpenAI 兼容 audio/transcriptions）。
 * response_format=verbose_json 时返回 segment 级时间戳。
 */
@Slf4j
@Component
public class GroqWhisperProvider implements AsrProvider {

    private final AifanyiProperties.Asr.Groq cfg;
    private final RestClient client;
    private final ObjectMapper mapper;

    public GroqWhisperProvider(AifanyiProperties props, ObjectMapper mapper) {
        this.cfg = props.getAsr().getGroq();
        this.mapper = mapper;
        this.client = RestClient.create();
    }

    @Override
    public String name() {
        return "groq";
    }

    @Override
    public List<Segment> transcribe(Path audio, String language, AsrContext ctx) {
        String apiKey = (ctx != null && StringUtils.hasText(ctx.apiKey())) ? ctx.apiKey() : cfg.getApiKey();
        String model = (ctx != null && StringUtils.hasText(ctx.model())) ? ctx.model() : cfg.getModel();
        if (!StringUtils.hasText(apiKey)) {
            throw new BizException("未配置 Groq API Key，请在设置里填写或用环境变量");
        }

        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new FileSystemResource(audio));
        body.part("model", model);
        body.part("response_format", "verbose_json");
        if (StringUtils.hasText(language) && !"auto".equalsIgnoreCase(language)) {
            body.part("language", language);
        }

        String json;
        try {
            json = client.post()
                    .uri(cfg.getBaseUrl() + "/audio/transcriptions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body.build())
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new BizException("Groq 转写请求失败: " + e.getMessage());
        }

        return parse(json);
    }

    private List<Segment> parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode segs = root.path("segments");
            List<Segment> result = new ArrayList<>();
            if (segs.isArray() && !segs.isEmpty()) {
                for (JsonNode s : segs) {
                    long start = Math.round(s.path("start").asDouble() * 1000);
                    long end = Math.round(s.path("end").asDouble() * 1000);
                    String text = s.path("text").asText("").trim();
                    if (!text.isEmpty()) {
                        result.add(new Segment(start, end, text));
                    }
                }
            } else {
                // 兜底：没有 segments 时把整段文本作为一条
                String text = root.path("text").asText("").trim();
                if (!text.isEmpty()) {
                    result.add(new Segment(0, 0, text));
                }
            }
            return result;
        } catch (Exception e) {
            throw new BizException("解析 Groq 响应失败: " + e.getMessage());
        }
    }
}
