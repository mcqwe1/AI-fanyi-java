package com.aifanyi.ocr;

import com.aifanyi.agent.AgentHttp;
import com.aifanyi.asr.AiServiceLauncher;
import com.aifanyi.common.BizException;
import com.aifanyi.config.AifanyiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 调本机 ai-service 的图片 OCR（POST /ocr，RapidOCR/PP-OCRv4 离线模型）。
 *
 * <p>与 {@link com.aifanyi.agent.vector.EmbeddingClient} 的「绝不抛异常」相反：
 * OCR 是图片翻译的主链一环，失败必须把原因如实抛给用户——没有识别就没有可翻的文字，
 * 静默降级只会表现为「点了没反应」。
 */
@Slf4j
@Component
public class OcrClient {

    /** 首次调用要加载 OCR 模型（约 3s），大图识别本身也要几秒，给足余量。 */
    private static final long TIMEOUT_MS = 60_000;

    private final AifanyiProperties.Asr.Local cfg;
    private final ObjectMapper mapper;
    private final AgentHttp http;
    private final AiServiceLauncher launcher;

    public OcrClient(AifanyiProperties props, ObjectMapper mapper, AgentHttp http,
                     AiServiceLauncher launcher) {
        this.cfg = props.getAsr().getLocal();
        this.mapper = mapper;
        this.http = http;
        this.launcher = launcher;
    }

    /**
     * OCR 识别出的一行文字。box 为 [x, y, w, h] 轴对齐包围盒（原图像素坐标），
     * 供扩展把译文绘回图片原位；旧版 ai-service 或解析异常时为 null，调用方必须容忍缺失。
     */
    public record Line(String text, double[] box) {
    }

    /**
     * 识别图片中的文字行（按版面顺序，已滤掉空白行）。
     *
     * @param imageBase64 纯 base64（不带 data: 前缀）
     * @return 文字行（含包围盒坐标）；图里没有文字返回空列表（不是错误）
     */
    public List<Line> ocrLines(String imageBase64) {
        launcher.ensureRunning(true); // 不可用时抛带指引的 BizException（含自动拉起）
        String url = AgentHttp.join(cfg.getBaseUrl(), "/ocr");
        AgentHttp.Result r;
        try {
            ObjectNode req = mapper.createObjectNode();
            req.put("image_base64", imageBase64);
            r = http.postJson(url, Map.of(), mapper.writeValueAsString(req), TIMEOUT_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BizException("图片识别被中断");
        } catch (Exception e) {
            throw new BizException("图片识别服务调用失败: " + e.getMessage());
        }
        if (!r.ok()) {
            throw new BizException("图片识别失败：" + detailOf(r.body(), r.status()));
        }
        try {
            JsonNode lines = mapper.readTree(r.body()).path("lines");
            List<Line> out = new ArrayList<>();
            if (lines.isArray()) {
                for (JsonNode ln : lines) {
                    String t = ln.path("text").asText("");
                    if (t.isBlank()) {
                        continue;
                    }
                    double[] box = null;
                    JsonNode b = ln.path("box");
                    if (b.isArray() && b.size() == 4) {
                        box = new double[]{b.get(0).asDouble(), b.get(1).asDouble(),
                                b.get(2).asDouble(), b.get(3).asDouble()};
                    }
                    out.add(new Line(t.strip(), box));
                }
            }
            return out;
        } catch (Exception e) {
            throw new BizException("解析识别结果失败: " + e.getMessage());
        }
    }

    /** FastAPI 的错误体是 {"detail": "..."}，取不出来就报 HTTP 状态码。 */
    private String detailOf(String body, int status) {
        try {
            String d = mapper.readTree(body).path("detail").asText("");
            if (!d.isBlank()) {
                return d;
            }
        } catch (Exception ignore) {
            // 非 JSON 响应体，落到状态码兜底
        }
        return "HTTP " + status;
    }
}
