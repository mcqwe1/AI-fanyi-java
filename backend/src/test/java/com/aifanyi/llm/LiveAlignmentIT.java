package com.aifanyi.llm;

import com.aifanyi.agent.node.RagContextBuilder;
import com.aifanyi.asr.Segment;
import com.aifanyi.config.AifanyiProperties;
import com.aifanyi.media.SubtitleParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实模型下的对齐实测（<b>默认不跑</b>，只有显式传入 -Dlive.llm.key=... 时才启用）。
 *
 * <p>复现任务 258 的翻译链路：读一份原文 SRT，走 Agent 模式的 {@link RagContextBuilder}
 * （批大小 40、8 路并发、contextMode=source），把结果写成双语 SRT，供 tmp-probe/drift.py 检测错位。
 *
 * <pre>
 * mvnw test -Dtest=LiveAlignmentIT \
 *   -Dlive.llm.base=https://api.deepseek.com/v1 -Dlive.llm.key=sk-xxx \
 *   -Dlive.llm.model=deepseek-v4-flash \
 *   -Dlive.src=...\potato-en-source.srt -Dlive.out=...\potato-fixed.srt
 * </pre>
 */
@EnabledIfSystemProperty(named = "live.llm.key", matches = ".+")
class LiveAlignmentIT {

    @Test
    void 真实模型翻译并输出双语字幕供错位检测() throws Exception {
        String base = System.getProperty("live.llm.base", "https://api.deepseek.com/v1");
        String key = System.getProperty("live.llm.key");
        String model = System.getProperty("live.llm.model", "deepseek-v4-flash");
        String targetLang = System.getProperty("live.lang", "中文");
        Path src = Path.of(System.getProperty("live.src"));
        Path out = Path.of(System.getProperty("live.out"));

        List<Segment> segments = SubtitleParser.parse(Files.readString(src, StandardCharsets.UTF_8));
        assertFalse(segments.isEmpty(), "源字幕没解析出内容");
        List<String> sources = segments.stream().map(Segment::text).toList();
        System.out.println("[live] 原文 " + sources.size() + " 行，模型 " + model);

        AifanyiProperties props = new AifanyiProperties();
        ObjectMapper mapper = new ObjectMapper();
        OpenAiTranslator translator =
                new OpenAiTranslator(props, mapper, new MtTranslateClient(mapper));
        RagContextBuilder rag = new RagContextBuilder(translator, props);

        // 与线上一致：openai 协议、60s 超时、关思维链、批 40、并发 8
        LlmConfig cfg = new LlmConfig(base, key, model, LlmConfig.PROTO_OPENAI, 60,
                props.getLlm().isDisableThinking(), props.getLlm().getBatchSize(),
                props.getLlm().getConcurrency());

        long t0 = System.currentTimeMillis();
        RagContextBuilder.RagResult rr = rag.translate(sources, targetLang, cfg, null, null, null);
        long ms = System.currentTimeMillis() - t0;
        List<String> targets = rr.targets();
        assertEquals(sources.size(), targets.size(), "译文行数必须与原文一致");
        System.out.println("[live] 翻译完成 " + ms / 1000 + "s，未译 " + rr.missingLines().size() + " 行");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            sb.append(i + 1).append('\n')
                    .append(ts(s.startMs())).append(" --> ").append(ts(s.endMs())).append('\n')
                    .append(targets.get(i)).append('\n')
                    .append(s.text()).append('\n').append('\n');
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("[live] 已写出双语字幕: " + out);
    }

    private static String ts(long ms) {
        long h = ms / 3_600_000;
        long m = ms % 3_600_000 / 60_000;
        long s = ms % 60_000 / 1000;
        return String.format("%02d:%02d:%02d,%03d", h, m, s, ms % 1000);
    }

    /**
     * 同场次对照组：用<b>修复前的老协议</b>（不要求锚点、拿到行号就无条件回填）翻同一批原文，
     * 验证错位是否会复现——否则「修复后 0 错位」无法排除只是这一次运气好。
     *
     * <p>刻意不复用 {@link OpenAiTranslator}：那已经是修好的版本。这里直接照着老实现
     * 重发一遍 HTTP，是对照组该有的样子。
     */
    @Test
    @EnabledIfSystemProperty(named = "live.legacy", matches = "true")
    void 对照组_老协议下错位是否复现() throws Exception {
        String base = System.getProperty("live.llm.base", "https://api.deepseek.com/v1");
        String key = System.getProperty("live.llm.key");
        String model = System.getProperty("live.llm.model", "deepseek-v4-flash");
        String targetLang = System.getProperty("live.lang", "中文");
        Path src = Path.of(System.getProperty("live.src"));
        Path out = Path.of(System.getProperty("live.legacy.out"));

        List<Segment> segments = SubtitleParser.parse(Files.readString(src, StandardCharsets.UTF_8));
        List<String> sources = segments.stream().map(Segment::text).toList();
        System.out.println("[legacy] 原文 " + sources.size() + " 行，老协议对照组");

        ObjectMapper mapper = new ObjectMapper();
        int batchSize = 40;
        String[] targets = sources.toArray(new String[0]);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (int from = 0; from < sources.size(); from += batchSize) {
            final int start = from;
            final int end = Math.min(from + batchSize, sources.size());
            fs.add(pool.submit(() -> {
                List<String> batch = sources.subList(start, end);
                boolean[] filled = new boolean[batch.size()];
                for (int attempt = 1; attempt <= 3; attempt++) {
                    List<Integer> todo = new java.util.ArrayList<>();
                    for (int i = 0; i < batch.size(); i++) {
                        if (!filled[i]) {
                            todo.add(i);
                        }
                    }
                    if (todo.isEmpty()) {
                        break;
                    }
                    List<String> sub = todo.stream().map(batch::get).toList();
                    try {
                        String content = legacyChat(mapper, base, key, model, targetLang, sub);
                        // 老实现：拿到 i 就无条件回填，不做任何校验
                        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(
                                content.substring(content.indexOf('{'), content.lastIndexOf('}') + 1));
                        for (com.fasterxml.jackson.databind.JsonNode nd : root.path("translations")) {
                            int i = nd.path("i").asInt(-1);
                            String t = nd.path("t").asText("");
                            if (i < 0 || i >= todo.size() || t.isBlank()) {
                                continue;
                            }
                            int g = todo.get(i);
                            if (!filled[g]) {
                                targets[start + g] = t;
                                filled[g] = true;
                            }
                        }
                    } catch (Exception e) {
                        System.out.println("[legacy] 批 " + start + " 第 " + attempt + " 次失败: " + e);
                    }
                }
                return null;
            }));
        }
        for (java.util.concurrent.Future<?> f : fs) {
            f.get();
        }
        pool.shutdownNow();
        System.out.println("[legacy] 翻译完成 " + (System.currentTimeMillis() - t0) / 1000 + "s");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            sb.append(i + 1).append('\n')
                    .append(ts(s.startMs())).append(" --> ").append(ts(s.endMs())).append('\n')
                    .append(targets[i]).append('\n')
                    .append(s.text()).append('\n').append('\n');
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("[legacy] 已写出双语字幕: " + out);
    }

    /** 修复前那一版的请求：提示词里没有 s 锚点，也没有「不得合并行」的强调。 */
    private static String legacyChat(ObjectMapper mapper, String base, String key, String model,
                                     String targetLang, List<String> batch) throws Exception {
        String sys = "你是专业的视频字幕翻译。把 lines 数组里每个对象的 text 翻译成" + targetLang
                + "。要求：忠实自然、口语化、避免翻译腔；保留专有名词。"
                + "只返回 JSON 对象：{\"translations\": [{\"i\": 行号, \"t\": \"译文\"}]}，"
                + "其中 i 必须与输入对象的 i 一一对应；必须为每一个输入行各返回恰好一条，"
                + "不要合并、拆分或遗漏任何行，不要改变 i，不要输出任何额外文字。";
        var arr = mapper.createArrayNode();
        for (int i = 0; i < batch.size(); i++) {
            arr.addObject().put("i", i).put("text", batch.get(i));
        }
        var user = mapper.createObjectNode();
        user.set("lines", arr);

        var req = mapper.createObjectNode();
        req.put("model", model);
        req.put("temperature", 0.3);
        req.putObject("response_format").put("type", "json_object");
        req.putObject("thinking").put("type", "disabled");
        var msgs = req.putArray("messages");
        msgs.addObject().put("role", "system").put("content", sys);
        msgs.addObject().put("role", "user").put("content", mapper.writeValueAsString(user));

        var rf = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(java.time.Duration.ofSeconds(10));
        rf.setReadTimeout(java.time.Duration.ofSeconds(60));
        String resp = org.springframework.web.client.RestClient.builder().requestFactory(rf).build()
                .post().uri(base.replaceAll("/+$", "") + "/chat/completions")
                .header("Authorization", "Bearer " + key)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(req))
                .retrieve().body(String.class);
        return mapper.readTree(resp).path("choices").path(0).path("message").path("content").asText("");
    }
}
