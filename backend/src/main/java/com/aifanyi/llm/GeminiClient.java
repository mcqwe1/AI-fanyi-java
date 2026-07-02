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
     * @param transcript 整段转写文本
     * @param srcLang    源语言
     * @param tgtLang    目标语言
     * @param cfg        Gemini 有效配置（base/key/model，均来自用户设置页）
     */
    public List<TermCandidate> extractTerms(String transcript, String srcLang, String tgtLang, GeminiConfig cfg) {
        if (cfg == null || !StringUtils.hasText(cfg.apiKey())) {
            throw new BizException("Gemini 未配置，请在「设置 → API 密钥」填写");
        }
        List<String> chunks = splitByChars(transcript, CHUNK_CHARS);
        if (chunks.size() <= 1) {
            return extractOne(transcript, srcLang, tgtLang, cfg);
        }

        log.info("KB 抽术语：转写 {} 字，切 {} 块并行抽取", transcript.length(), chunks.size());
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(chunks.size(), MAX_PARALLEL));
        try {
            List<Future<List<TermCandidate>>> futures = new ArrayList<>();
            for (String c : chunks) {
                futures.add(pool.submit(() -> extractOne(c, srcLang, tgtLang, cfg)));
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

    /** 对一段（块）转写做一次联网抽取。 */
    private List<TermCandidate> extractOne(String transcript, String srcLang, String tgtLang, GeminiConfig cfg) {
        String url = cfg.baseUrl().replaceAll("/+$", "") + "/chat/completions";

        String sys = "你是字幕翻译的术语顾问。给你一段" + srcLang + "语音的转写文本。"
                + "请抽取其中需要在翻译成" + tgtLang + "时【统一且正确】译法的术语，只限以下六大类："
                + "①人名；②地名与地址；③机构组织与品牌名；④作品名/标题；"
                + "⑤文化专有项（食物、习俗、节日等）；⑥科技术语中的专有名词（如阿尔兹海默症、CRISPR）。"
                + "普通词汇、常见动词形容词不要列。"
                + "【译法语言铁律】每个术语的 target 必须是【" + tgtLang + "】，是给" + tgtLang + "观众看的。"
                + "严禁用英文、罗马音或源语言原文直接当译法——除非该专名在" + tgtLang + "受众里本就通用其英文/原文写法"
                + "（如 Sony、iPhone、官方英文名）。"
                + "【怎么定译法】① 先联网搜索该专名在" + tgtLang + "里有没有官方/通用译名，有就用"
                + "（例：日文人名「本多」→「本多」而非「本田」）；"
                + "② 查不到现成译名时，自己产出" + tgtLang + "译法：含义清楚的优先意译；含义不清或属生造词的就"
                + "音译成" + tgtLang + "文字，并保留通用类别词。绝不能因为“没有现成译名”就退回英文/原文。"
                + "（示例，当目标语言为中文时：イヤポッコクラブ→「耳波扣俱乐部」，不要写成 Ear Poko Club；"
                + "リアちゃん→「莉亚酱」；〜さん→「〜先生/小姐」。）"
                + "只返回 JSON：{\"terms\":[{\"source\":\"原文\",\"target\":\"" + tgtLang + "译法\","
                + "\"category\":\"六大类之一的中文名（人名/地名与地址/机构组织与品牌/作品名/文化专有项/科技专名）\","
                + "\"note\":\"简短依据，注明 官方译名/意译/音译\"}]}，不要输出任何额外文字、解释或 markdown 代码块。";

        ObjectNode req = mapper.createObjectNode();
        req.put("model", cfg.model());
        req.put("temperature", 0.2);
        ArrayNode messages = req.putArray("messages");
        messages.addObject().put("role", "system").put("content", sys);
        messages.addObject().put("role", "user").put("content", "转写文本：\n" + transcript);

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
