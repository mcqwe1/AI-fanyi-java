package com.aifanyi.tts;

import com.aifanyi.asr.AiServiceLauncher;
import com.aifanyi.common.BizException;
import com.aifanyi.config.AifanyiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Locale;

/**
 * Edge-TTS（微软语音）：经本机 ai-service 的 POST /tts 转发合成，免费免 Key（需联网）。
 * ai-service 未运行时自动拉起（照 VadClient 范式）。
 */
@Slf4j
@Component
public class EdgeTtsProvider implements TtsProvider {

    private final AifanyiProperties.Asr.Local cfg;
    private final ObjectMapper mapper;
    private final AiServiceLauncher launcher;
    private final RestClient client;

    public EdgeTtsProvider(AifanyiProperties props, ObjectMapper mapper, AiServiceLauncher launcher) {
        this.cfg = props.getAsr().getLocal();
        this.mapper = mapper;
        this.launcher = launcher;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        // 单段文本很短，但微软端偶尔抖动；给足余量
        rf.setReadTimeout(Duration.ofSeconds(120));
        this.client = RestClient.builder().requestFactory(rf).build();
    }

    @Override
    public String name() {
        return "edge";
    }

    @Override
    public byte[] synthesize(String text, TtsConfig unused, TtsOptions opts) {
        launcher.ensureRunning(true);
        String url = cfg.getBaseUrl().replaceAll("/+$", "") + "/tts";
        ObjectNode req = mapper.createObjectNode();
        req.put("text", text);
        req.put("voice", opts.voice());
        req.put("rate", speedToRate(opts.speed()));
        try {
            byte[] audio = client.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(req))
                    .retrieve()
                    .body(byte[].class);
            if (audio == null || audio.length == 0) {
                throw new BizException("Edge-TTS 返回空音频（音色 " + opts.voice() + " 可能无效）");
            }
            return audio;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("404")) {
                throw new BizException("本机 ai-service 版本过旧（无 /tts 接口），请更新完整包或重启后端");
            }
            throw new BizException("Edge-TTS 合成失败: " + msg);
        }
    }

    /** 语速 1.0=常速 → edge-tts 的 rate 百分比格式（如 1.2 → "+20%"，0.8 → "-20%"）。 */
    private static String speedToRate(double speed) {
        int pct = (int) Math.round((speed - 1.0) * 100);
        return String.format(Locale.ROOT, "%s%d%%", pct >= 0 ? "+" : "-", Math.abs(pct));
    }
}
