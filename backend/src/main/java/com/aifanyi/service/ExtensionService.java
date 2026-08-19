package com.aifanyi.service;

import com.aifanyi.common.BizException;
import com.aifanyi.controller.dto.ExtDtos.ExtImageLine;
import com.aifanyi.controller.dto.ExtDtos.ExtImageReq;
import com.aifanyi.controller.dto.ExtDtos.ExtImageResp;
import com.aifanyi.controller.dto.ExtDtos.ExtTranslateReq;
import com.aifanyi.controller.dto.ExtDtos.ExtTranslateResp;
import com.aifanyi.controller.dto.TextTranslateDtos.Line;
import com.aifanyi.entity.TextTranslation;
import com.aifanyi.llm.LlmConfig;
import com.aifanyi.llm.LlmTranslator;
import com.aifanyi.mapper.TextTranslationMapper;
import com.aifanyi.ocr.OcrClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 狐译浏览器扩展后端：批量翻译（划词/整页/图片/输入框）。
 * 2026-08 需求改版：划词/整页/图片/输入框翻译各写一条聚合历史（channel=ext_*），
 * 悬停翻译太碎不写。这些记录只进「划词翻译」页的历史，不再混入文本 AI 翻译历史
 * （见 {@link TextTranslateService#extHistory(Long)}）。批大小策略与 {@link TextTranslateService}
 * 相同：按平均段长动态调批。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExtensionService {

    /** 单次请求段数上限（扩展端整页翻译按 40 段/批切块，正常远到不了这个数） */
    private static final int MAX_ITEMS = 300;
    /** 单次请求总字符上限 */
    private static final int MAX_TOTAL_CHARS = 30_000;
    /** 动态批大小的目标字符量（与文本翻译模式一致） */
    private static final int TARGET_BATCH_CHARS = 3000;
    /** 图片翻译：解码后字节上限 8MB（与 ai-service /ocr 侧一致） */
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final int PREVIEW_CHARS = 50;

    private final SettingsService settings;
    private final LlmTranslator translator;
    private final OcrClient ocrClient;
    private final TextTranslationMapper historyMapper;
    private final ObjectMapper json;

    public ExtTranslateResp translate(Long userId, ExtTranslateReq req) {
        List<String> texts = req.texts();
        if (texts == null || texts.isEmpty()) {
            throw new BizException("没有要翻译的文本");
        }
        if (texts.size() > MAX_ITEMS) {
            throw new BizException("单次最多 " + MAX_ITEMS + " 段，请分批请求");
        }
        String targetLang = StringUtils.hasText(req.targetLang()) ? req.targetLang().trim() : "中文";

        // 空白段不送翻原样返回；其余按序编号（translate 契约：等长同序）
        int[] sendIdx = new int[texts.size()];
        List<String> toSend = new ArrayList<>();
        long sendChars = 0;
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i) == null ? "" : texts.get(i);
            if (t.strip().isEmpty()) {
                sendIdx[i] = -1;
            } else {
                sendIdx[i] = toSend.size();
                toSend.add(t);
                sendChars += t.length();
            }
        }
        if (toSend.isEmpty()) {
            throw new BizException("没有可翻译的内容");
        }
        if (sendChars > MAX_TOTAL_CHARS) {
            throw new BizException("单次请求过大（" + sendChars + " 字符，上限 " + MAX_TOTAL_CHARS + "）");
        }

        LlmConfig base = settings.effectiveLlm(userId);
        long avgLen = Math.max(1, sendChars / toSend.size());
        int batch = (int) Math.max(1, Math.min(40, TARGET_BATCH_CHARS / avgLen));
        // withBatchSize 保留协议与超时；重新 new 会把 claude/机翻协议打回 openai（见 LlmConfig 注释）
        LlmConfig cfg = base.withBatchSize(batch);
        log.info("扩展翻译：{} 段 / {} 字符 → {}，batchSize={}，模型 {}",
                toSend.size(), sendChars, targetLang, batch, cfg.model());

        long t0 = System.currentTimeMillis();
        List<String> out = translator.translate(toSend, targetLang, cfg, Map.of(), null);
        long elapsed = System.currentTimeMillis() - t0;

        List<String> translations = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            translations.add(sendIdx[i] < 0 ? texts.get(i) : out.get(sendIdx[i]));
        }
        // 划词/整页/输入框各写一条聚合历史（进划词翻译自己的历史）；悬停（hover）太碎、旧版扩展（kind 缺省）不写
        String channel = channelOf(req.kind());
        if (channel != null) {
            saveHistory(userId, channel, targetLang, texts, translations, cfg.model(), elapsed);
        }
        return new ExtTranslateResp(translations, cfg.model(), elapsed);
    }

    /** 扩展来源 kind → 历史渠道；返回 null 表示这次不写历史。 */
    private static String channelOf(String kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "selection" -> "ext_text";
            case "page" -> "ext_page";
            case "input" -> "ext_input";
            default -> null;    // hover 等
        };
    }

    /**
     * 图片翻译 = 本地 OCR 提文字 + 现有文本翻译链路。
     * <p>不走视觉大模型：那要求用户配多模态模型，而产品推荐的 DeepSeek 等纯文本模型
     * 都会直接失败。OCR（RapidOCR，随包离线）把问题拆回「翻译一段文本」，
     * 任何配好的翻译模型都能用。
     */
    public ExtImageResp translateImage(Long userId, ExtImageReq req) {
        if (!StringUtils.hasText(req.imageBase64())) {
            throw new BizException("没有图片数据");
        }
        byte[] bytes;
        try {
            // MIME 解码器容忍换行等分隔符（个别 canvas/dataURL 实现会带）
            bytes = Base64.getMimeDecoder().decode(req.imageBase64());
        } catch (IllegalArgumentException e) {
            throw new BizException("图片数据不是合法的 base64");
        }
        if (bytes.length == 0) {
            throw new BizException("没有图片数据");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new BizException("图片过大（" + (bytes.length / 1024 / 1024) + "MB，上限 8MB）");
        }
        long t0 = System.currentTimeMillis();
        // 重新编码：保证送去 OCR 的一定是干净 base64
        List<OcrClient.Line> ocr = ocrClient.ocrLines(Base64.getEncoder().encodeToString(bytes));
        log.info("扩展图片翻译：{} KB → OCR {} 行，耗时 {}ms",
                bytes.length / 1024, ocr.size(), System.currentTimeMillis() - t0);
        if (ocr.isEmpty()) {
            return new ExtImageResp("（图中未识别到文字）", "", List.of(), "", System.currentTimeMillis() - t0);
        }
        // 极端密集的截图可能上千行，超过文本链路单次上限时截断保头部（真实场景几乎碰不到）
        if (ocr.size() > MAX_ITEMS) {
            log.warn("OCR 行数 {} 超上限，截断至 {}", ocr.size(), MAX_ITEMS);
            ocr = ocr.subList(0, MAX_ITEMS);
        }
        List<String> texts = ocr.stream().map(OcrClient.Line::text).toList();
        ExtTranslateResp tr = translate(userId, new ExtTranslateReq(texts, req.targetLang(), null));
        List<ExtImageLine> lines = new ArrayList<>(ocr.size());
        for (int i = 0; i < ocr.size(); i++) {
            double[] b = ocr.get(i).box();
            lines.add(new ExtImageLine(texts.get(i), tr.translations().get(i),
                    b == null ? null : List.of(b[0], b[1], b[2], b[3])));
        }
        long elapsed = System.currentTimeMillis() - t0;
        saveHistory(userId, "ext_image", StringUtils.hasText(req.targetLang()) ? req.targetLang().trim() : "中文",
                texts, tr.translations(), tr.model(), elapsed);
        return new ExtImageResp(String.join("\n", tr.translations()), String.join("\n", texts),
                lines, tr.model(), elapsed);
    }

    /**
     * 写一条聚合历史（channel=ext_*）。历史是旁路：写失败只记日志，不影响翻译结果返回。
     */
    private void saveHistory(Long userId, String channel, String targetLang,
                             List<String> sources, List<String> targets, String model, long elapsedMs) {
        try {
            List<Line> pairs = new ArrayList<>(sources.size());
            for (int i = 0; i < sources.size(); i++) {
                pairs.add(new Line(sources.get(i), i < targets.size() ? targets.get(i) : ""));
            }
            String sourceText = String.join("\n", sources);
            TextTranslation row = new TextTranslation();
            row.setUserId(userId);
            row.setChannel(channel);
            row.setTargetLang(targetLang);
            row.setPreview(preview(sourceText));
            row.setSourceText(sourceText);
            row.setPlainTarget(String.join("\n", targets));
            row.setPairsJson(json.writeValueAsString(pairs));
            row.setModel(model);
            row.setElapsedMs(elapsedMs);
            row.setUntranslatedLines(0);
            historyMapper.insert(row);
        } catch (Exception e) {
            log.warn("扩展翻译历史写入失败（不影响结果）：{}", e.getMessage());
        }
    }

    private static String preview(String text) {
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS) + "…";
    }

    /**
     * 把扩展目录打成 zip 流给前端下载。目录探测：便携版 start.bat 会 cd 到包根
     * （extension 与 app/bin 平级），开发期 user.dir 是 backend/（extension 在上一级）。
     */
    public void writeExtensionZip(OutputStream os) throws IOException {
        Path dir = locateExtensionDir();
        if (dir == null) {
            throw new BizException("未找到扩展目录 extension/（便携包内应与 start.bat 同级）");
        }
        try (ZipOutputStream zos = new ZipOutputStream(os); Stream<Path> walk = Files.walk(dir)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path f : files) {
                // 顶层带 huyi-extension/ 前缀，解压即得完整目录，路径分隔符统一为 /
                String entry = "huyi-extension/" + dir.relativize(f).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(entry));
                Files.copy(f, zos);
                zos.closeEntry();
            }
        }
    }

    public Path locateExtensionDir() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path[] candidates = {cwd.resolve("extension"), cwd.getParent() == null ? null : cwd.getParent().resolve("extension")};
        for (Path p : candidates) {
            if (p != null && Files.isRegularFile(p.resolve("manifest.json"))) {
                return p;
            }
        }
        return null;
    }
}
