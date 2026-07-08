package com.aifanyi.asr;

import com.aifanyi.common.BizException;
import com.aifanyi.config.AifanyiProperties;
import com.aifanyi.media.FfmpegService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Groq 云 Whisper（OpenAI 兼容 audio/transcriptions）。
 * response_format=verbose_json 时返回 segment 级时间戳。
 * 文件超过 maxChunkBytes 时按静音点自动分块、逐块转写、时间戳加偏移后拼接（Groq 免费档限约 25MB）。
 */
@Slf4j
@Component
public class GroqWhisperProvider implements AsrProvider {

    private final AifanyiProperties.Asr.Groq cfg;
    private final RestClient client;
    private final ObjectMapper mapper;
    private final FfmpegService ffmpeg;

    public GroqWhisperProvider(AifanyiProperties props, ObjectMapper mapper, FfmpegService ffmpeg) {
        this.cfg = props.getAsr().getGroq();
        this.mapper = mapper;
        this.ffmpeg = ffmpeg;
        this.client = RestClient.create();
    }

    @Override
    public String name() {
        return "groq";
    }

    @Override
    public List<Segment> transcribe(Path audio, String language, AsrContext ctx) {
        String apiKey = ctx == null ? null : ctx.apiKey();
        String model = (ctx != null && StringUtils.hasText(ctx.model())) ? ctx.model() : cfg.getModel();
        if (!StringUtils.hasText(apiKey)) {
            throw new BizException("未配置 Groq API Key，请在「设置 → API 密钥」填写");
        }

        long size;
        try {
            size = Files.size(audio);
        } catch (Exception e) {
            size = 0;
        }
        long maxBytes = cfg.getMaxChunkBytes() > 0 ? cfg.getMaxChunkBytes() : 20L * 1024 * 1024;

        // 未超限：直接单次转写（短文件零额外开销）
        if (size <= maxBytes) {
            return transcribeOne(audio, apiKey, model, language);
        }

        // 超限：按时长估算块数，切点吸附静音，逐块转写后偏移拼接
        double totalSec = ffmpeg.probeDurationSec(audio);
        if (totalSec <= 0) {
            log.warn("Groq 音频超限但探测不到时长，回退单次调用（可能仍 413）: {}", audio);
            return transcribeOne(audio, apiKey, model, language);
        }

        double targetBytes = maxBytes * 0.8;                 // 留安全余量，避免吸附后某块超限
        int numChunks = (int) Math.max(2, Math.ceil(size / targetBytes));
        double nominalChunk = totalSec / numChunks;
        List<long[]> silences = ffmpeg.detectSilenceMs(audio, -50, 0.8);
        List<Double> bounds = computeBoundaries(totalSec, numChunks, nominalChunk, silences);

        log.info("Groq 音频 {} 超限({}MB > {}MB)，分 {} 块转写",
                audio.getFileName(), size / 1024 / 1024, maxBytes / 1024 / 1024, bounds.size() - 1);

        List<Segment> all = new ArrayList<>();
        Path dir = audio.getParent();
        // 语言锁定：auto 时各块独立检测易中途漂移（日文音频冒英文句），用首块检测结果锁定后续块
        String lockedLang = language;
        for (int i = 0; i < bounds.size() - 1; i++) {
            double start = bounds.get(i);
            double dur = bounds.get(i + 1) - start;
            if (dur <= 0.1) {
                continue;
            }
            Path chunk = dir.resolve("groqchunk_" + i + ".mp3");
            try {
                ffmpeg.cutAudio(audio, chunk, start, dur);
                JsonNode root = readJson(callGroq(chunk, apiKey, model, lockedLang));
                List<Segment> parsed = parse(root);
                if (!StringUtils.hasText(lockedLang) || "auto".equalsIgnoreCase(lockedLang)) {
                    String det = detectedLangCode(root);
                    // 全部段都被反幻觉过滤掉的块（纯噪声/静音），其语言检测基于噪声，不能拿来锁定
                    if (det != null && !parsed.isEmpty()) {
                        lockedLang = det;
                        log.info("Groq 分块转写语言锁定为 {}（据首个有效块检测）", det);
                    }
                }
                long offset = Math.round(start * 1000);
                for (Segment s : parsed) {
                    all.add(new Segment(s.startMs() + offset, s.endMs() + offset, s.text()));
                }
            } finally {
                try {
                    Files.deleteIfExists(chunk);
                } catch (Exception ignore) {
                    // 临时块文件清理失败不影响结果
                }
            }
        }
        return all;
    }

    /**
     * 计算 numChunks 块的边界时间点（秒）；bounds.size()==numChunks+1，首 0 末 totalSec。
     * 内部边界吸附到名义点附近的静音段中点，避免把词切断；找不到合适静音则用名义时间点。
     */
    private List<Double> computeBoundaries(double totalSec, int numChunks,
                                           double nominalChunk, List<long[]> silences) {
        List<Double> bounds = new ArrayList<>();
        bounds.add(0.0);
        double prev = 0.0;
        double window = nominalChunk * 0.2;                  // 只在名义点 ±20% 内找静音，限制块大小波动
        for (int i = 1; i < numChunks; i++) {
            double nominal = i * nominalChunk;
            double best = nominal;
            double bestDist = Double.MAX_VALUE;
            if (silences != null) {
                for (long[] iv : silences) {
                    double sSec = iv[0] / 1000.0;
                    double eSec = (iv[1] == Long.MAX_VALUE) ? totalSec : iv[1] / 1000.0;
                    double mid = (sSec + eSec) / 2.0;
                    double dist = Math.abs(mid - nominal);
                    if (dist <= window && dist < bestDist && mid > prev + 1.0 && mid < totalSec - 1.0) {
                        best = mid;
                        bestDist = dist;
                    }
                }
            }
            if (best <= prev + 1.0) {
                best = nominal;                              // 保证严格递增
            }
            bounds.add(best);
            prev = best;
        }
        bounds.add(totalSec);
        return bounds;
    }

    /** 单文件一次 Groq 转写，返回该文件内（相对 0 起）的分段。 */
    private List<Segment> transcribeOne(Path file, String apiKey, String model, String language) {
        return parse(readJson(callGroq(file, apiKey, model, language)));
    }

    /** 发起一次 Groq 转写请求，返回原始 verbose_json。 */
    private String callGroq(Path file, String apiKey, String model, String language) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new FileSystemResource(file));
        body.part("model", model);
        body.part("response_format", "verbose_json");
        // 同时取 segment 与 word 级时间戳，用 word 收紧字幕起止，避免静音期提前出现
        body.part("timestamp_granularities[]", "segment");
        body.part("timestamp_granularities[]", "word");
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

        return json;
    }

    /** verbose_json 的 language 是英文全名（如 japanese）；映射为 ISO 代码用于锁定后续块。 */
    private static final Map<String, String> LANG_NAME_TO_CODE = Map.ofEntries(
            Map.entry("japanese", "ja"), Map.entry("english", "en"),
            Map.entry("chinese", "zh"), Map.entry("korean", "ko"),
            Map.entry("french", "fr"), Map.entry("german", "de"),
            Map.entry("spanish", "es"), Map.entry("russian", "ru"),
            Map.entry("portuguese", "pt"), Map.entry("italian", "it"),
            Map.entry("thai", "th"), Map.entry("vietnamese", "vi"),
            Map.entry("arabic", "ar"), Map.entry("hindi", "hi"),
            Map.entry("indonesian", "id"), Map.entry("dutch", "nl"));

    /** 从 verbose_json 提取检测语言并转 ISO 代码；取不到/不认识返回 null（不锁定）。 */
    private String detectedLangCode(JsonNode root) {
        String name = root.path("language").asText("").trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return null;
        }
        if (name.length() == 2) {
            return name;
        }
        String code = LANG_NAME_TO_CODE.get(name);
        if (code == null) {
            log.debug("Groq 检测语言 {} 不在映射表中，本块不锁定语言", name);
        }
        return code;
    }

    /** 解析 Groq 返回的 verbose_json 文本为 JSON 树；失败抛业务异常。 */
    private JsonNode readJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new BizException("解析 Groq 响应失败: " + e.getMessage());
        }
    }

    /** 教科书式静音/噪声幻觉判定阈值：openai-whisper 的默认跳窗条件
     *  （本地路径 faster-whisper 内部已按同阈值整窗丢弃，此处为 Groq 路径补齐同一行为）。 */
    private static final double HALLUCINATION_NO_SPEECH_PROB = 0.6;
    private static final double HALLUCINATION_AVG_LOGPROB = -1.0;

    private List<Segment> parse(JsonNode root) {
        try {
            JsonNode segs = root.path("segments");
            JsonNode words = root.path("words");
            List<Segment> result = new ArrayList<>();
            if (segs.isArray() && !segs.isEmpty()) {
                int dropped = 0;
                for (JsonNode s : segs) {
                    String text = s.path("text").asText("").trim();
                    if (text.isEmpty()) {
                        continue;
                    }
                    // 教科书式静音/噪声幻觉特征：模型自认多半没在说话且置信度低 → 丢弃
                    double noSpeech = s.path("no_speech_prob").asDouble(0);
                    double logProb = s.path("avg_logprob").asDouble(0);
                    if (noSpeech > HALLUCINATION_NO_SPEECH_PROB && logProb < HALLUCINATION_AVG_LOGPROB) {
                        dropped++;
                        continue;
                    }
                    double segStart = s.path("start").asDouble();
                    double segEnd = s.path("end").asDouble();
                    // 用 word 时间戳收紧到“第一个词出声 ~ 最后一个词结束”
                    double[] tight = tightenWithWords(words, segStart, segEnd);
                    long start = Math.round(tight[0] * 1000);
                    long end = Math.round(tight[1] * 1000);
                    result.add(new Segment(start, end, text));
                }
                if (dropped > 0) {
                    log.info("Groq 反幻觉：丢弃 {} 条低置信度段(no_speech>0.6 & logprob<-1.0)", dropped);
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

    /**
     * 用落在该 segment 时间范围内的词，取首词 start 与末词 end，收紧字幕显示区间。
     * 没有可用 word 时回退到 segment 原始 start/end。
     */
    private double[] tightenWithWords(JsonNode words, double segStart, double segEnd) {
        if (words == null || !words.isArray() || words.isEmpty()) {
            return new double[]{segStart, segEnd};
        }
        final double eps = 0.1;
        double first = Double.NaN;
        double last = Double.NaN;
        for (JsonNode w : words) {
            double ws = w.path("start").asDouble();
            double we = w.path("end").asDouble();
            // 词的起点落在该段范围内即归属本段
            if (ws >= segStart - eps && ws < segEnd + eps) {
                if (Double.isNaN(first)) {
                    first = ws;
                }
                last = we;
            }
        }
        if (Double.isNaN(first) || Double.isNaN(last) || last <= first) {
            return new double[]{segStart, segEnd};
        }
        return new double[]{first, last};
    }
}
