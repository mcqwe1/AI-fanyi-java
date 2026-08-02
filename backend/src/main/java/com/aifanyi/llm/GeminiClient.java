package com.aifanyi.llm;

import com.aifanyi.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Gemini 客户端。通过 OpenAI 兼容端点调用（本地 vertex2openai 代理转 Vertex AI）。
 * 模型用 -search 后缀变体即开启 Google 搜索 grounding，让模型联网核实人名/专名的权威译法。
 *
 * 抽术语耗时随转写长度【超线性】增长（联网搜索量失控）：实测约 3500 字 ~21s、约 7000 字 ~320s。
 * 故长转写按字数切块、并行抽取、按 source 去重合并——把墙钟压回单块量级。
 */
@Slf4j
@Component
public class GeminiClient {

    /** 一条术语候选：原文术语 + 推荐译法 + 类别 + 依据说明。 */
    public record TermCandidate(String source, String target, String category, String note) {
    }

    /** 单块字符上限：保持每次调用落在“快”区间（小块联网快得多；约 3500 字以上联网耗时会失控）。 */
    private static final int CHUNK_CHARS = 2000;
    /** 并行抽取的最大并发块数（避免压垮代理/触发配额）。 */
    private static final int MAX_PARALLEL = 6;

    private final ObjectMapper mapper;
    private final RestClient client;

    public GeminiClient(ObjectMapper mapper) {
        this.mapper = mapper;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(10));
        // 单块本应在 ~半分钟内返回；给足余量但别太长，卡住的块尽早失败不拖累整体
        rf.setReadTimeout(Duration.ofSeconds(120));
        this.client = RestClient.builder().requestFactory(rf).build();
    }

    /**
     * 联网从转写文本中抽取需统一译法的术语（人名/地名/作品名/组织名/角色名等）。
     * 长转写自动切块并行抽取并按 source 去重。
     *
     * @param transcript     整段转写文本
     * @param srcLang        源语言
     * @param tgtLang        目标语言
     * @param cfg            Gemini 有效配置（base/key/model，均来自用户设置页）
     * @param promptTemplate 自定义系统提示词模板（用户在设置页填写）；空则用内置 {@link #defaultTermPrompt}。
     *                       支持占位符 {sourceLang}/{targetLang}；转写文本始终作为 user 消息附上，
     *                       模板里的 {transcript} 占位符（如有）也会被替换。
     */
    public List<TermCandidate> extractTerms(String transcript, String srcLang, String tgtLang,
                                            GeminiConfig cfg, String promptTemplate) {
        if (cfg == null || !StringUtils.hasText(cfg.apiKey())) {
            throw new BizException("Gemini 未配置，请在「设置 → API 密钥」填写");
        }
        List<String> chunks = splitByChars(transcript, CHUNK_CHARS);
        if (chunks.size() <= 1) {
            return extractOne(transcript, srcLang, tgtLang, cfg, promptTemplate);
        }

        log.info("KB 抽术语：转写 {} 字，切 {} 块并行抽取", transcript.length(), chunks.size());
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(chunks.size(), MAX_PARALLEL));
        try {
            List<Future<List<TermCandidate>>> futures = new ArrayList<>();
            for (String c : chunks) {
                futures.add(pool.submit(() -> extractOne(c, srcLang, tgtLang, cfg, promptTemplate)));
            }
            // 按 source 去重（保留先出现的）；upsert 还会再按已有库去重一次
            Map<String, TermCandidate> merged = new LinkedHashMap<>();
            for (Future<List<TermCandidate>> f : futures) {
                try {
                    for (TermCandidate tc : f.get()) {
                        merged.putIfAbsent(tc.source(), tc);
                    }
                } catch (Exception e) {
                    log.warn("某分块抽术语失败，已跳过该块: {}", e.getMessage());
                }
            }
            return new ArrayList<>(merged.values());
        } finally {
            pool.shutdownNow();
        }
    }

    /** 按字符数把整段转写切成若干块，尽量在换行处断开，不切断单行。 */
    private List<String> splitByChars(String transcript, int maxChars) {
        List<String> chunks = new ArrayList<>();
        if (transcript == null) {
            return chunks;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : transcript.split("\n", -1)) {
            if (sb.length() > 0 && sb.length() + line.length() + 1 > maxChars) {
                chunks.add(sb.toString());
                sb.setLength(0);
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        if (sb.length() > 0) {
            chunks.add(sb.toString());
        }
        return chunks;
    }

    /** 对一段（块）转写做一次联网抽取。promptTemplate 为空则用内置模板；两者同用占位符机制。 */
    private List<TermCandidate> extractOne(String transcript, String srcLang, String tgtLang,
                                           GeminiConfig cfg, String promptTemplate) {
        String url = cfg.baseUrl().replaceAll("/+$", "") + "/chat/completions";

        String tmpl = StringUtils.hasText(promptTemplate) ? promptTemplate : defaultTermPrompt();
        // 统一替换占位符；模板含 {transcript} 则就地注入转写，同时 user 消息始终附一份兜底
        String sys = fillPlaceholders(tmpl, srcLang, tgtLang, transcript);
        String userMsg = "转写文本：\n" + transcript;

        ObjectNode req = mapper.createObjectNode();
        req.put("model", cfg.model());
        req.put("temperature", 0.2);
        ArrayNode messages = req.putArray("messages");
        messages.addObject().put("role", "system").put("content", sys);
        messages.addObject().put("role", "user").put("content", userMsg);

        String resp;
        try {
            resp = client.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(req))
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new BizException("Gemini 请求失败: " + e.getMessage());
        }
        return parse(resp);
    }

    /**
     * 内置术语抽取提示词模板（2026-07-15 结构化重写）。
     * 占位符与用户自定义模板同一套规则：{sourceLang}/{targetLang} 必替换，
     * {transcript}（可选）替换为转写全文；不写转写也会作为 user 消息附上。
     * public 供设置页原样展示，用户可复制为自定义起点。
     */
    public static String defaultTermPrompt() {
        return """
                你是专业的字幕翻译术语顾问。任务：从一段{sourceLang}语音的转写文本中，找出翻译成{targetLang}时必须【全篇统一、译法正确】的专有名词，产出术语表。

                【只收录以下六类】
                1. 人名（含昵称、艺名、角色名）
                2. 地名与地址
                3. 机构、组织、品牌名
                4. 作品名/标题（影视、歌曲、书籍、游戏、节目等）
                5. 文化专有项（特色食物、习俗、节日等）
                6. 科技专名（疾病、技术、产品型号等，如 CRISPR、阿尔兹海默症）
                【不要收录】普通名词、动词、形容词、常见短语。只有"反复出现且容易译错/译乱"的专名才值得进表；宁缺毋滥。

                【译法怎么定——按顺序执行】
                ① 先联网核实：该专名在{targetLang}里是否已有官方或公认通用译名，有就照用（例：Einstein 在中文的公认译名是「爱因斯坦」，直接采用）。切勿对已有通用译名的专名自创新译，也不要把同音/近形的其他专名混为一谈——转写里写的是谁就查谁。
                ② 查不到现成译名：含义清楚的优先意译（如虚构社团 Moonlight Bakery Club →「月光烘焙社」）；生造词或含义不明的，音译成{targetLang}文字；人名昵称的敬称后缀按{targetLang}习惯转换（如日语「〜さん」→「〜先生/小姐」）。
                ③ 铁律：target 必须写成{targetLang}，是给{targetLang}观众看的。严禁把英文、罗马音或源语言原文直接当译法——除非该专名在{targetLang}受众中本就通用其原文写法（如 Sony、iPhone、官方英文名）。

                【输出格式】只返回如下 JSON，不要任何解释、前后缀或 markdown 代码块：
                {"terms":[{"source":"原文","target":"{targetLang}译法","category":"人名/地名与地址/机构组织与品牌/作品名/文化专有项/科技专名 之一","note":"一句话依据，注明 官方译名/意译/音译"}]}""";
    }

    /** 把模板里的占位符替换为实际值；transcript 为 null 时不替换 {transcript}。 */
    private static String fillPlaceholders(String tmpl, String srcLang, String tgtLang, String transcript) {
        String out = tmpl.replace("{sourceLang}", srcLang == null ? "" : srcLang)
                .replace("{targetLang}", tgtLang == null ? "" : tgtLang);
        if (transcript != null) {
            out = out.replace("{transcript}", transcript);
        }
        return out;
    }

    private List<TermCandidate> parse(String resp) {
        List<TermCandidate> out = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(resp);
            // OpenAI 兼容：choices[0].message.content
            String text = root.path("choices").path(0).path("message").path("content").asText("").trim();
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                log.warn("Gemini 未返回可解析的术语 JSON: {}", text.length() > 200 ? text.substring(0, 200) : text);
                return out;
            }
            JsonNode terms = mapper.readTree(text.substring(start, end + 1)).path("terms");
            if (terms.isArray()) {
                for (JsonNode t : terms) {
                    String s = t.path("source").asText("").trim();
                    String tg = t.path("target").asText("").trim();
                    if (!s.isEmpty() && !tg.isEmpty()) {
                        out.add(new TermCandidate(s, tg,
                                t.path("category").asText("").trim(),
                                t.path("note").asText("").trim()));
                    }
                }
            }
        } catch (Exception e) {
            throw new BizException("解析 Gemini 响应失败: " + e.getMessage());
        }
        return out;
    }
}
