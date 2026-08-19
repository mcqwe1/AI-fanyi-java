package com.aifanyi.llm;

import com.aifanyi.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 传统机器翻译引擎客户端：谷歌翻译 / 微软翻译 / DeepL。
 * <p>与 LLM 的差异：无提示词能力（风格/术语/上下文全部忽略），按段直接翻译；
 * 目标语言从中文名映射到各家语言码，映射不到就明确报错而不是瞎猜。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MtTranslateClient {

    private final ObjectMapper mapper;

    /** 每次请求最多带多少段（三家上限都 ≥50，取保守值） */
    private static final int BATCH = 50;

    /** 单批产出：与输入等长的译文（失败处保留原文）+ 未译成功的批内下标。 */
    public record MtOutcome(List<String> lines, List<Integer> missing) {
    }

    /** 整体翻译入口：内部分批 + 每批重试两次，失败批保留原文并记入 missing。 */
    public MtOutcome translateAll(List<String> sources, String targetLang, LlmConfig cfg) {
        if (!StringUtils.hasText(cfg.apiKey())) {
            throw new BizException("未配置 " + engineName(cfg.protocol()) + " 的 API Key，请在「设置 → API 配置 → 大语言模型」填写");
        }
        String[] out = sources.toArray(new String[0]);
        List<Integer> missing = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (int off = 0; off < sources.size(); off += BATCH) {
            List<String> batch = sources.subList(off, Math.min(off + BATCH, sources.size()));
            List<String> translated = null;
            for (int attempt = 1; attempt <= 2 && translated == null; attempt++) {
                try {
                    translated = translateBatch(batch, targetLang, cfg);
                } catch (BizException be) {
                    throw be;                       // 配置类错误（语言不支持等）没必要重试
                } catch (Exception e) {
                    log.warn("{} 批量翻译失败(第{}次, {}段): {}", engineName(cfg.protocol()), attempt, batch.size(), e.getMessage());
                    if (attempt < 2) {
                        try {
                            Thread.sleep(1200);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            if (translated != null && translated.size() == batch.size()) {
                for (int i = 0; i < batch.size(); i++) {
                    if (StringUtils.hasText(translated.get(i))) {
                        out[off + i] = translated.get(i);
                    } else {
                        missing.add(off + i);
                    }
                }
            } else {
                for (int i = 0; i < batch.size(); i++) {
                    missing.add(off + i);
                }
            }
        }
        log.info("{} 翻译完成: {} 段, 失败 {} 段, 耗时 {}ms",
                engineName(cfg.protocol()), sources.size(), missing.size(), System.currentTimeMillis() - t0);
        return new MtOutcome(new ArrayList<>(List.of(out)), missing);
    }

    private List<String> translateBatch(List<String> batch, String targetLang, LlmConfig cfg) throws Exception {
        return switch (cfg.protocol()) {
            case LlmConfig.PROTO_DEEPL -> deepl(batch, targetLang, cfg);
            case LlmConfig.PROTO_GOOGLE_MT -> google(batch, targetLang, cfg);
            case LlmConfig.PROTO_MS_MT -> microsoft(batch, targetLang, cfg);
            default -> throw new BizException("未知机翻协议: " + cfg.protocol());
        };
    }

    // ─────────────────────────── DeepL ───────────────────────────

    private List<String> deepl(List<String> batch, String targetLang, LlmConfig cfg) throws Exception {
        String lang = code(DEEPL, targetLang, "DeepL");
        ObjectNode req = mapper.createObjectNode();
        ArrayNode text = req.putArray("text");
        batch.forEach(text::add);
        req.put("target_lang", lang);
        String body = client(cfg).post()
                .uri(strip(cfg.baseUrl()) + "/translate")
                .header("Authorization", "DeepL-Auth-Key " + cfg.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(req))
                .retrieve()
                .body(String.class);
        JsonNode arr = mapper.readTree(body).path("translations");
        List<String> out = new ArrayList<>(batch.size());
        for (JsonNode n : arr) {
            out.add(n.path("text").asText(""));
        }
        return out;
    }

    // ─────────────────────────── 谷歌翻译（Cloud Translation v2，API Key 认证） ───────────────────────────

    private List<String> google(List<String> batch, String targetLang, LlmConfig cfg) throws Exception {
        String lang = code(GOOGLE, targetLang, "谷歌翻译");
        ObjectNode req = mapper.createObjectNode();
        ArrayNode q = req.putArray("q");
        batch.forEach(q::add);
        req.put("target", lang);
        req.put("format", "text");
        String body = client(cfg).post()
                .uri(strip(cfg.baseUrl()) + "?key=" + cfg.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(req))
                .retrieve()
                .body(String.class);
        JsonNode arr = mapper.readTree(body).path("data").path("translations");
        List<String> out = new ArrayList<>(batch.size());
        for (JsonNode n : arr) {
            out.add(n.path("translatedText").asText(""));
        }
        return out;
    }

    // ─────────────────────────── 微软翻译（Translator v3） ───────────────────────────

    private List<String> microsoft(List<String> batch, String targetLang, LlmConfig cfg) throws Exception {
        String lang = code(MICROSOFT, targetLang, "微软翻译");
        ArrayNode req = mapper.createArrayNode();
        for (String s : batch) {
            req.addObject().put("Text", s);
        }
        var spec = client(cfg).post()
                .uri(strip(cfg.baseUrl()) + "/translate?api-version=3.0&to=" + lang)
                .header("Ocp-Apim-Subscription-Key", cfg.apiKey())
                .contentType(MediaType.APPLICATION_JSON);
        // 多服务/区域资源必须带区域头（存放在 model 字段位；全球资源留空）
        if (StringUtils.hasText(cfg.model())) {
            spec = spec.header("Ocp-Apim-Subscription-Region", cfg.model().trim());
        }
        String body = spec.body(mapper.writeValueAsString(req)).retrieve().body(String.class);
        JsonNode arr = mapper.readTree(body);
        List<String> out = new ArrayList<>(batch.size());
        for (JsonNode n : arr) {
            out.add(n.path("translations").path(0).path("text").asText(""));
        }
        return out;
    }

    // ─────────────────────────── 公共 ───────────────────────────

    private RestClient client(LlmConfig cfg) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(10));
        rf.setReadTimeout(Duration.ofSeconds(cfg.effectiveTimeoutSec()));
        return RestClient.builder().requestFactory(rf).build();
    }

    private static String strip(String url) {
        return url == null ? "" : url.trim().replaceAll("/+$", "");
    }

    public static String engineName(String protocol) {
        return switch (protocol) {
            case LlmConfig.PROTO_DEEPL -> "DeepL";
            case LlmConfig.PROTO_GOOGLE_MT -> "谷歌翻译";
            case LlmConfig.PROTO_MS_MT -> "微软翻译";
            default -> protocol;
        };
    }

    private static String code(Map<String, String> table, String targetLang, String engine) {
        String t = targetLang == null ? "" : targetLang.trim();
        String c = table.get(t);
        if (c == null) {
            throw new BizException(engine + " 不支持目标语言「" + targetLang + "」，请换用大模型服务商翻译该语言");
        }
        return c;
    }

    /** 目标语言中文名 → 各家语言码（app 内语言名见前端 constants/langs.js） */
    private static final Map<String, String> GOOGLE = new LinkedHashMap<>();
    private static final Map<String, String> MICROSOFT = new LinkedHashMap<>();
    private static final Map<String, String> DEEPL = new LinkedHashMap<>();

    private static void put(String zhName, String google, String ms, String deepl) {
        GOOGLE.put(zhName, google);
        MICROSOFT.put(zhName, ms);
        if (deepl != null) {
            DEEPL.put(zhName, deepl);
        }
    }

    static {
        put("中文", "zh-CN", "zh-Hans", "ZH-HANS");
        put("繁体中文", "zh-TW", "zh-Hant", "ZH-HANT");
        put("英语", "en", "en", "EN");
        put("日语", "ja", "ja", "JA");
        put("韩语", "ko", "ko", "KO");
        put("法语", "fr", "fr", "FR");
        put("德语", "de", "de", "DE");
        put("西班牙语", "es", "es", "ES");
        put("葡萄牙语", "pt", "pt", "PT-PT");
        put("意大利语", "it", "it", "IT");
        put("荷兰语", "nl", "nl", "NL");
        put("俄语", "ru", "ru", "RU");
        put("乌克兰语", "uk", "uk", "UK");
        put("波兰语", "pl", "pl", "PL");
        put("捷克语", "cs", "cs", "CS");
        put("匈牙利语", "hu", "hu", "HU");
        put("罗马尼亚语", "ro", "ro", "RO");
        put("希腊语", "el", "el", "EL");
        put("瑞典语", "sv", "sv", "SV");
        put("挪威语", "no", "nb", "NB");
        put("丹麦语", "da", "da", "DA");
        put("芬兰语", "fi", "fi", "FI");
        put("塞尔维亚语", "sr", "sr-Cyrl", null);
        put("克罗地亚语", "hr", "hr", null);
        put("保加利亚语", "bg", "bg", "BG");
        put("阿拉伯语", "ar", "ar", "AR");
        put("土耳其语", "tr", "tr", "TR");
        put("波斯语", "fa", "fa", null);
        put("希伯来语", "iw", "he", "HE");
        put("印地语", "hi", "hi", null);
        put("孟加拉语", "bn", "bn", null);
        put("乌尔都语", "ur", "ur", null);
        put("泰米尔语", "ta", "ta", null);
        put("泰卢固语", "te", "te", null);
        put("泰语", "th", "th", "TH");
        put("越南语", "vi", "vi", "VI");
        put("印尼语", "id", "id", "ID");
        put("马来语", "ms", "ms", null);
        put("菲律宾语", "tl", "fil", null);
        put("缅甸语", "my", "my", null);
        put("高棉语", "km", "km", null);
        put("老挝语", "lo", "lo", null);
        put("斯瓦希里语", "sw", "sw", null);
    }
}
