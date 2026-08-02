package com.aifanyi.tts;

import com.aifanyi.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * OpenAI 兼容 TTS：POST {base}/audio/speech，body {model,input,voice,speed,response_format}。
 * 适用于官方 OpenAI TTS 及各类兼容中转端点。
 */
@Slf4j
@Component
public class OpenAiCompatTtsProvider implements TtsProvider {

    private final RestClient client;

    public OpenAiCompatTtsProvider() {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(10));
        // 单段字幕文本很短，但中转端点偶尔排队；给足余量避免长任务中途断
        rf.setReadTimeout(Duration.ofSeconds(120));
        this.client = RestClient.builder().requestFactory(rf).build();
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public byte[] synthesize(String text, TtsConfig cfg, TtsOptions opts) {
        String url = cfg.baseUrl().replaceAll("/+$", "") + "/audio/speech";
        // 音色原样发送：各引擎的格式差异（如硅基流动的 "模型:音色" 全称）已在 TtsEngines 音色映射里吸收
        try {
            byte[] audio = client.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", cfg.model(),
                            "input", text,
                            "voice", opts.voice(),
                            "speed", opts.speed(),
                            "response_format", "mp3"))
                    .retrieve()
                    .body(byte[].class);
            if (audio == null || audio.length == 0) {
                throw new BizException("TTS 返回空音频");
            }
            return audio;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("TTS 合成失败: " + e.getMessage());
        }
    }
}
