package com.aifanyi.service;

import com.aifanyi.asr.AsrContext;
import com.aifanyi.asr.AsrProvider;
import com.aifanyi.asr.AsrProviderFactory;
import com.aifanyi.asr.Segment;
import com.aifanyi.asr.VadClient;
import com.aifanyi.common.BizException;
import com.aifanyi.entity.TranslationTask;
import com.aifanyi.media.FfmpegService;
import com.aifanyi.media.HallucinationFilter;
import com.aifanyi.media.SpeechGapFinder;
import com.aifanyi.media.SubtitleTimingFixer;
import com.aifanyi.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;

/**
 * 媒体转写：抽音频 → ASR（VAD 并行）→ 反幻觉 → 时间轴对齐 → 兜底修复。
 * 从 TaskPipeline 原样抽出（行为逐字一致），供普通/KB 流水线与 Agent 流水线共用——
 * 这段链路与翻译模式无关，任何模式拿到的都应是同一份「已清洗、已对轴」的分段。
 *
 * <p>时间轴处理顺序不可调换，详见 {@link SubtitleTimingFixer} 类注释。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaTranscribeService {

    private final StorageService storage;
    private final FfmpegService ffmpeg;
    private final AsrProviderFactory asrFactory;
    private final VadClient vadClient;
    private final SettingsService settings;

    /**
     * 转写并修正时间轴。会写入 task.audioPath（调用方负责持久化 task）。
     *
     * @param task        任务（用其 videoPath/asrProvider/sourceLang/userId）
     * @param onAudioDone 音频抽取完成回调（把 audioPath 落库的时机由调用方决定）
     * @param asrProgress ASR 进度回调（0~1），可为 null
     * @return 已清洗、已对轴的分段；为空时抛 BizException
     */
    public List<Segment> transcribe(TranslationTask task, Runnable onAudioDone, DoubleConsumer asrProgress) {
        Long taskId = task.getId();
        // ── 快路：用户自带原文字幕 → 直接拿它的时间轴与文本，整段 ASR 全部跳过 ──
        // 语音识别是全流程最贵的一段（实测 16.8 分钟视频里 126s / 140s，占九成），
        // 用户手上已经有字幕时没有任何理由再听一遍。
        List<Segment> supplied = loadSuppliedSubtitle(task);
        if (supplied != null) {
            if (asrProgress != null) {
                asrProgress.accept(1.0);
            }
            log.info("任务 {} 使用自带字幕 {} 条，跳过抽音频与语音识别", taskId, supplied.size());
            return supplied;
        }
        // 1. 抽音频
        Path video = Path.of(task.getVideoPath());
        Path audio = storage.resolve(taskId, "audio.mp3");
        ffmpeg.extractAudio(video, audio);
        task.setAudioPath(audio.toString());
        if (onAudioDone != null) {
            onAudioDone.run();
        }
        // VAD 与 ASR 并行：只依赖已抽出的音频，整段耗时藏进转写里（结果在吸附前 join）
        CompletableFuture<List<long[]>> vadFuture =
                CompletableFuture.supplyAsync(() -> vadClient.speechRegionsMs(audio));

        // 2. ASR 转写（按用户设置解析 provider/model/key）
        AsrRoute route = resolveAsr(task.getAsrProvider(), task.getUserId());
        AsrProvider asr = asrFactory.get(route.provider());
        List<Segment> segments = asr.transcribe(audio, normLang(task.getSourceLang()),
                new AsrContext(route.apiKey(), route.model(), asrProgress));
        if (segments.isEmpty()) {
            throw new BizException("未识别到语音内容");
        }
        // VAD 语音区间（与 ASR 并行计算，此处 join）：缺口补翻、幻觉丢弃、时间轴对齐都靠它。
        // ai-service 不可用则降级 ffmpeg 静音检测（只能兜底真静音处的幻觉，不做补翻与对齐）。
        List<long[]> vadRegions = null;
        try {
            vadRegions = vadFuture.join();
        } catch (Exception e) {
            log.warn("VAD 不可用，缺口补翻与时间轴对齐跳过、幻觉过滤退回 ffmpeg 静音检测: {}", e.getMessage());
        }

        // ── 缺口补翻（治漏翻的关键一环）──
        // Whisper 的 VAD 门卫（任何阈值）都会拦掉部分轻声段，被拦的音频模型根本没见过，
        // 句子从源头就不存在。对账找缺口 → 剪出来单独重转 → 补回，保证
        // 「VAD 听到有人说话的地方，必然尝试过转写」。在反幻觉之前做：补回的段同样要过清洗。
        int refilled = 0;
        if (vadRegions != null && !vadRegions.isEmpty()) {
            List<Segment> extra = refillGaps(taskId, audio, segments, vadRegions, route,
                    normLang(task.getSourceLang()));
            if (!extra.isEmpty()) {
                refilled = extra.size();
                segments = new java.util.ArrayList<>(segments);
                segments.addAll(extra);
                segments.sort(java.util.Comparator.comparingLong(Segment::startMs));
            }
        }

        int rawCount = segments.size();
        // 文本层反幻觉：黑名单套话 + 复读折叠（对所有 provider 生效）
        segments = HallucinationFilter.filter(segments);
        int afterHallu = segments.size();
        // VAD 时间轴处理（区间在上面已 join）
        if (vadRegions != null && !vadRegions.isEmpty()) {
            // ① 与语音区间几乎零重叠的段 = 幻觉（文本无关，比黑名单更通用）
            segments = SubtitleTimingFixer.dropNonSpeech(segments, vadRegions);
            // ② 双向对齐：早出后移、晚出前拉、终点修剪/外扩
            segments = SubtitleTimingFixer.alignToSpeech(segments, vadRegions);
        } else {
            List<long[]> silence = ffmpeg.detectSilenceMs(audio, -50, 0.8);
            segments = SubtitleTimingFixer.dropSilenceHallucinations(segments, silence);
        }
        if (segments.isEmpty()) {
            throw new BizException("未识别到有效语音（疑似全为静音/幻觉）");
        }
        // ③ 补回音视频流起始偏移（抽音频丢失 start_time 差 → 轴相对视频恒定错位）
        long avOffsetMs = ffmpeg.probeAvStartOffsetMs(video);
        if (avOffsetMs != 0) {
            segments = SubtitleTimingFixer.shiftAll(segments, avOffsetMs);
        }
        // ④ 最终兜底：去空白、去重叠（避免下一句字幕提前出现）、保证最短时长
        List<Segment> fixed = SubtitleTimingFixer.fix(segments);
        // 漏翻排查用的总账：每一道关口各删了多少，一眼看出是谁删的
        log.info("转写清洗账单：ASR {} 条（含缺口补翻找回 {} 条）→ 反幻觉后 {} 条 → "
                        + "时间轴过滤后 {} 条 → 最终 {} 条（清洗丢弃 {} 条，占 {}%）",
                rawCount, refilled, afterHallu, segments.size(), fixed.size(),
                rawCount - fixed.size(),
                rawCount == 0 ? 0 : Math.round((rawCount - fixed.size()) * 1000.0 / rawCount) / 10.0);
        return fixed;
    }

    // ---- 自带字幕直翻 ----

    /**
     * 读取任务携带的原文字幕并解析成分段；没带字幕返回 null（走正常转写链路）。
     *
     * <p>刻意<b>不做</b>反幻觉、VAD 对轴、缺口补翻那一套：那些是给机器识别结果纠错用的，
     * 用户自己的字幕是人工产物，轴和内容都该原样尊重，系统去"修"只会帮倒忙。
     * 只做一层最基础的收尾（{@link SubtitleTimingFixer#fix}）——去空白、去重叠、保证最短时长，
     * 这几项对任何来源的字幕都成立。
     *
     * <p>字幕文件损坏/解析不出内容时直接抛错，而不是悄悄退回去跑 ASR：
     * 用户明确带了字幕，静默换一条路会让他等两分钟还不知道为什么结果不对。
     */
    private List<Segment> loadSuppliedSubtitle(TranslationTask task) {
        String path = task.getSubtitleSourcePath();
        if (path == null || path.isBlank()) {
            return null;
        }
        Path f = Path.of(path);
        if (!java.nio.file.Files.exists(f)) {
            throw new BizException("自带字幕文件已丢失，请重新上传或改用语音识别");
        }
        String content;
        try {
            content = com.aifanyi.media.CharsetSniffer.decode(java.nio.file.Files.readAllBytes(f));
        } catch (Exception e) {
            throw new BizException("读取自带字幕失败: " + e.getMessage());
        }
        List<Segment> segs = com.aifanyi.media.SubtitleParser.parse(content);
        if (segs.isEmpty()) {
            throw new BizException("自带字幕里没解析出任何一条内容，请确认是有效的 SRT/VTT 文件");
        }
        return SubtitleTimingFixer.fix(segs);
    }

    // ---- 缺口补翻参数（经验值，见 SpeechGapFinder 注释）----
    /** 短于 1.2s 的缺口不补：多为呼吸/词间停顿，重转一次 ASR 不值当 */
    private static final long GAP_MIN_MS = 1_200;
    /** 缺口两侧各外扩 250ms：切音频贴边会把词切一半 */
    private static final long GAP_PAD_MS = 250;
    /** 相邻缺口相距 800ms 内合并成一刀，少发请求 */
    private static final long GAP_MERGE_MS = 800;
    /** 单任务最多补几刀（防极端素材把任务拖死；按缺口时长降序取前 N） */
    private static final int GAP_MAX_SLICES = 24;

    /**
     * 把「有语音无字幕」的缺口逐段剪出来重转。
     * <p>任何一刀失败都只是放弃那一刀（降级哲学：补翻是增益，不是依赖）。
     */
    private List<Segment> refillGaps(Long taskId, Path audio, List<Segment> segs,
                                     List<long[]> vadRegions, AsrRoute route, String lang) {
        List<long[]> gaps = SpeechGapFinder.find(segs, vadRegions,
                GAP_MIN_MS, GAP_PAD_MS, GAP_MERGE_MS);
        if (gaps.isEmpty()) {
            return List.of();
        }
        if (gaps.size() > GAP_MAX_SLICES) {
            gaps = new java.util.ArrayList<>(gaps);
            gaps.sort((a, b) -> Long.compare(b[1] - b[0], a[1] - a[0]));   // 长缺口优先
            gaps = gaps.subList(0, GAP_MAX_SLICES);
            gaps.sort(java.util.Comparator.comparingLong(a -> a[0]));
        }
        long totalMs = gaps.stream().mapToLong(g -> g[1] - g[0]).sum();
        log.info("缺口补翻：发现 {} 段「有语音无字幕」共 {}s，逐段重转", gaps.size(), totalMs / 1000);

        AsrProvider asr = asrFactory.get(route.provider());
        List<Segment> out = new java.util.ArrayList<>();
        int slice = 0;
        for (long[] g : gaps) {
            slice++;
            Path cut = storage.resolve(taskId, "gap-" + slice + ".mp3");
            try {
                ffmpeg.cutAudio(audio, cut, g[0] / 1000.0, (g[1] - g[0]) / 1000.0);
                List<Segment> got = asr.transcribe(cut, lang,
                        new AsrContext(route.apiKey(), route.model(), null));
                for (Segment s : got) {
                    // 切片内时间 → 全局时间；越界（whisper 偶尔超切片长）钳回缺口内
                    long st = Math.max(g[0], g[0] + s.startMs());
                    long en = Math.min(g[1], g[0] + s.endMs());
                    if (en > st && s.text() != null && !s.text().isBlank()) {
                        out.add(new Segment(st, en, s.text()));
                    }
                }
            } catch (Exception e) {
                log.warn("缺口补翻第 {} 刀失败（跳过不影响其余）: {}", slice, e.getMessage());
            } finally {
                try {
                    java.nio.file.Files.deleteIfExists(cut);
                } catch (Exception ignored) {
                    // 清理失败无害
                }
            }
        }
        log.info("缺口补翻完成：{} 刀找回 {} 条字幕", slice, out.size());
        for (Segment s : out) {
            log.info("  [补回] {}~{} 「{}」", SubtitleTimingFixer.ts(s.startMs()),
                    SubtitleTimingFixer.ts(s.endMs()), s.text());
        }
        return out;
    }

    /** 把人类语言标签规范化为 Whisper/Groq 的 ISO 代码；识别不了返回 "auto"（自动检测）。 */
    private static final Map<String, String> LANG_CODE = Map.ofEntries(
            Map.entry("日语", "ja"), Map.entry("日文", "ja"), Map.entry("japanese", "ja"),
            Map.entry("英语", "en"), Map.entry("英文", "en"), Map.entry("english", "en"),
            Map.entry("韩语", "ko"), Map.entry("韩文", "ko"), Map.entry("korean", "ko"),
            Map.entry("中文", "zh"), Map.entry("汉语", "zh"), Map.entry("chinese", "zh"),
            Map.entry("法语", "fr"), Map.entry("德语", "de"), Map.entry("西班牙语", "es"),
            Map.entry("俄语", "ru"), Map.entry("葡萄牙语", "pt"), Map.entry("意大利语", "it"),
            Map.entry("泰语", "th"), Map.entry("越南语", "vi"), Map.entry("印尼语", "id"),
            Map.entry("阿拉伯语", "ar"), Map.entry("印地语", "hi"), Map.entry("荷兰语", "nl"));

    public static String normLang(String v) {
        if (v == null || v.isBlank()) return "auto";
        String k = v.trim();
        if ("auto".equalsIgnoreCase(k)) return "auto";
        String mapped = LANG_CODE.get(k);
        if (mapped != null) return mapped;
        mapped = LANG_CODE.get(k.toLowerCase());
        if (mapped != null) return mapped;
        // 已是 2 字母代码就直接用，否则交给自动检测
        return (k.length() == 2 && k.chars().allMatch(Character::isLetter)) ? k.toLowerCase() : "auto";
    }

    /** 把前端 ASR 选择值解析为 (provider, model, apiKey)。 */
    public AsrRoute resolveAsr(String selector, Long userId) {
        String sel = selector == null ? "groq" : selector.trim().toLowerCase();
        switch (sel) {
            case "groq":
                return new AsrRoute("groq", null, settings.effectiveGroqKey(userId));
            case "groq-turbo":
                return new AsrRoute("groq", "whisper-large-v3-turbo", settings.effectiveGroqKey(userId));
            case "qwen":
                return new AsrRoute("qwen", null, settings.effectiveDashscopeKey(userId));
            case "glm":
                return new AsrRoute("glm", null, settings.effectiveZhipuKey(userId));
            default:
                if (sel.startsWith("local")) {
                    String size = sel.contains("-") ? sel.substring(sel.indexOf('-') + 1) : "large-v3";
                    return new AsrRoute("local", size, null);
                }
                return new AsrRoute("groq", null, settings.effectiveGroqKey(userId));
        }
    }

    public record AsrRoute(String provider, String model, String apiKey) {
    }
}
