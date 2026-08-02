package com.aifanyi.service;

import com.aifanyi.common.BizException;
import com.aifanyi.config.AifanyiProperties;
import com.aifanyi.domain.MediaKind;
import com.aifanyi.domain.TaskStatus;
import com.aifanyi.entity.Subtitle;
import com.aifanyi.entity.TranslationTask;
import com.aifanyi.mapper.SubtitleMapper;
import com.aifanyi.mapper.TranslationTaskMapper;
import com.aifanyi.media.FfmpegService;
import com.aifanyi.media.WavTrackAssembler;
import com.aifanyi.storage.StorageService;
import com.aifanyi.tts.TtsConfig;
import com.aifanyi.tts.TtsEngines;
import com.aifanyi.tts.TtsOptions;
import com.aifanyi.tts.TtsProvider;
import com.aifanyi.tts.TtsProviderFactory;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TTS 配音：音色试听 + 并发逐行合成 → WAV 采样级拼轨 → 混入原视频。
 * <p>
 * 时间轴设计（2026-07-27 重做）：每行配音解码为 44.1kHz PCM WAV 后，
 * 由 {@link WavTrackAssembler} 直接写到整条轨道的「绝对采样位置」（= 字幕 startMs），
 * 空隙天然静音、重叠区混音——某行超长只影响它自己与下一行的衔接，
 * 误差绝不向后累积（旧 concat 传送带方案的级联顺延 + mp3 帧量化漂移已根治）。
 * 超出槽位的行先用 atempo 变速压缩（保音高，上限 {@link #MAX_TEMPO}）。
 * <p>
 * 速度设计：逐行合成走独立线程池并发（TTS_CONCURRENCY，默认 6），
 * 每行只起 1~2 个 ffmpeg 进程（解码 + 可选变速），静音/拼接零进程。
 * 配音状态仍独立于主状态机（dub_status 三列），失败不影响翻译成果。
 */
@Slf4j
@Service
public class TtsService {

    /** 超长行变速压缩上限（atempo 保音高；1.4x 以内语速自然，超过会显得赶） */
    private static final double MAX_TEMPO = 1.4;
    /** 超出槽位不足此值不做变速：几十毫秒的溢出听不出来，不值得重采样一遍 */
    private static final long TEMPO_TOLERANCE_MS = 120;
    /** 每行合成失败重试次数（edge-tts 等网络服务偶发抖动） */
    private static final int SYNTH_ATTEMPTS = 3;

    private final TranslationTaskMapper taskMapper;
    private final SubtitleMapper subtitleMapper;
    private final StorageService storage;
    private final FfmpegService ffmpeg;
    private final SettingsService settings;
    private final TtsProviderFactory ttsFactory;
    private final ExecutorService synthPool;

    public TtsService(TranslationTaskMapper taskMapper, SubtitleMapper subtitleMapper,
                      StorageService storage, FfmpegService ffmpeg,
                      SettingsService settings, TtsProviderFactory ttsFactory,
                      AifanyiProperties props) {
        this.taskMapper = taskMapper;
        this.subtitleMapper = subtitleMapper;
        this.storage = storage;
        this.ffmpeg = ffmpeg;
        this.settings = settings;
        this.ttsFactory = ttsFactory;
        int c = Math.max(1, props.getTts().getConcurrency());
        this.synthPool = Executors.newFixedThreadPool(c, r -> {
            Thread t = new Thread(r, "tts-synth");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        synthPool.shutdownNow();
    }

    /** 用户当前引擎的预置音色映射（未选择引擎时返回空列表，前端引导去设置页）。 */
    public List<TtsEngines.VoiceInfo> voices(Long userId) {
        TtsEngines.Engine e = TtsEngines.byId(settings.ttsEngineId(userId));
        return e == null ? List.of() : e.voices();
    }

    /** 引擎 id → provider 实现名：edge 走本机 ai-service，其余（siliconflow/openai）都是 OpenAI 兼容协议。 */
    private TtsProvider providerFor(Long userId) {
        String engineId = settings.ttsEngineId(userId);
        return ttsFactory.get("edge".equalsIgnoreCase(engineId) ? "edge" : "openai");
    }

    /** 音色试听：取任务第一条较长译文合成一句，返回 mp3 字节。 */
    public byte[] previewSample(TranslationTask task, Long userId, String voice, Double speed) {
        String text = sampleText(task.getId());
        TtsConfig cfg = settings.effectiveTts(userId);
        return providerFor(userId).synthesize(text, cfg,
                new TtsOptions(voice, speed == null ? 1.0 : speed));
    }

    /**
     * 异步配音：并发逐行 TTS+解码（0~76）→ WAV 拼轨（~80）→ 混入原视频（82~99）。
     * 全程只动 dubStatus/dubProgress/dubError，绝不碰主 status——配音是 DONE 任务的
     * 附加操作，失败也不该影响翻译成果。
     */
    @Async("mediaExecutor")
    public void dubAsync(Long taskId, Long userId) {
        TranslationTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.error("配音失败，任务不存在: {}", taskId);
            return;
        }
        try {
            task.setDubStatus("DUBBING");
            task.setDubProgress(0);
            task.setDubError(null);
            task.setDubNotice(null);
            taskMapper.updateById(task);

            List<Subtitle> subs = subtitleMapper.selectList(
                    Wrappers.<Subtitle>lambdaQuery()
                            .eq(Subtitle::getTaskId, taskId)
                            .orderByAsc(Subtitle::getSeq))
                    .stream()
                    .filter(s -> s.getTargetText() != null && !s.getTargetText().isBlank())
                    .toList();
            if (subs.isEmpty()) {
                throw new BizException("没有可配音的译文字幕");
            }

            TtsConfig cfg = settings.effectiveTts(userId);
            TtsProvider provider = providerFor(userId);
            TtsOptions opts = new TtsOptions(task.getTtsVoice(),
                    task.getTtsSpeed() == null ? 1.0 : Double.parseDouble(task.getTtsSpeed()));

            Path video = Path.of(task.getVideoPath());
            long videoDurMs = Math.round(ffmpeg.probeDurationSec(video) * 1000);

            // 工作目录：任务目录下 dub/（重跑配音先清旧片段）
            Path dubDir = storage.resolve(taskId, "dub");
            deleteRecursively(dubDir);
            Files.createDirectories(dubDir);

            // ---- 并发合成 + 解码 + 变速对齐（进度 0~76）----
            long t0 = System.currentTimeMillis();
            AtomicInteger done = new AtomicInteger();
            List<Future<PlacedClip>> futures = new ArrayList<>(subs.size());
            for (int i = 0; i < subs.size(); i++) {
                final int idx = i;
                futures.add(synthPool.submit(() ->
                        synthOne(idx, subs, videoDurMs, provider, cfg, opts, dubDir, task, done)));
            }
            List<PlacedClip> clips = new ArrayList<>(subs.size());
            BizException failure = null;
            for (Future<PlacedClip> f : futures) {
                if (failure != null) {
                    f.cancel(true);
                    continue;
                }
                try {
                    clips.add(f.get());
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    failure = cause instanceof BizException be
                            ? be : new BizException("配音合成失败: " + cause.getMessage());
                }
            }
            if (failure != null) {
                throw failure;
            }
            String notice = fitNotice(clips, subs);
            logFitSummary(clips, t0);

            // ---- WAV 采样级拼轨（进度 ~80）：每段钉在自己的 startMs 上，零累积误差 ----
            long lastEnd = clips.stream().mapToLong(c -> c.startMs() + c.durMs()).max().orElse(0);
            long totalMs = Math.max(videoDurMs, lastEnd);
            Path dubTrack = dubDir.resolve("dub_track.wav");
            List<WavTrackAssembler.Clip> placed = clips.stream()
                    .map(c -> new WavTrackAssembler.Clip(c.wav(), c.startMs()))
                    .toList();
            WavTrackAssembler.assemble(placed, totalMs, dubTrack);
            dubProgress(task, 80);

            // ---- 混入原视频（进度 82~99；视频流直拷）；「保留原声」在无音频流的视频上自动降级为替换 ----
            boolean keepBg = task.getTtsKeepOriginal() != null && task.getTtsKeepOriginal() == 1
                    && ffmpeg.probeHasAudio(video);
            Path out = storage.resolve(taskId, "dubbed.mp4");
            ffmpeg.muxAudioIntoVideo(video, dubTrack, out, keepBg, videoDurMs / 1000.0,
                    pct -> dubProgress(task, 82 + pct * 17 / 100));

            task.setDubVideoPath(out.toString());
            task.setDubStatus("DONE");
            task.setDubProgress(100);
            task.setDubNotice(notice);
            taskMapper.updateById(task);
            log.info("配音完成: {} -> {} ({} 行, 总耗时 {}s)", taskId, out, subs.size(),
                    (System.currentTimeMillis() - t0) / 1000);
        } catch (Exception e) {
            log.error("配音失败: {}", taskId, e);
            // 只标配音失败，主 status 与字幕成果原样保留，改完配置可直接重新配音
            task.setDubStatus("FAILED");
            task.setDubError(truncate(e.getMessage(), 900));
            taskMapper.updateById(task);
        }
    }

    /** 一行配音的落位结果。 */
    private record PlacedClip(int index, Path wav, long startMs, long durMs,
                              boolean tempoFitted, long overflowMs) {
    }

    /**
     * 合成一行：TTS（带重试）→ 解码 44.1kHz PCM WAV → 超槽则 atempo 压缩。
     * 槽位 = 本行 startMs 到下一行 startMs（末行到视频结尾）；行间静音天然是余量，
     * 压缩后仍超出的部分与下一行重叠混音，不影响后续任何行的位置。
     */
    private PlacedClip synthOne(int idx, List<Subtitle> subs, long videoDurMs,
                                TtsProvider provider, TtsConfig cfg, TtsOptions opts,
                                Path dubDir, TranslationTask task, AtomicInteger done) throws Exception {
        Subtitle s = subs.get(idx);
        byte[] audio = synthesizeWithRetry(provider, s.getTargetText().trim(), cfg, opts, s.getSeq());
        Path raw = dubDir.resolve("raw_" + idx + ".bin");
        Files.write(raw, audio);
        Path clip = dubDir.resolve("clip_" + idx + ".wav");
        ffmpeg.decodeToWavMono44k(raw, clip);
        Files.deleteIfExists(raw);
        long durMs = WavTrackAssembler.durationMs(clip);

        long slotMs = slotMs(idx, subs, videoDurMs);
        double tempo = fitTempo(durMs, slotMs);
        boolean fitted = false;
        if (tempo > 1.0) {
            Path fit = dubDir.resolve("fit_" + idx + ".wav");
            ffmpeg.atempoWav(clip, fit, tempo);
            Files.deleteIfExists(clip);
            clip = fit;
            durMs = WavTrackAssembler.durationMs(fit);
            fitted = true;
        }
        long overflow = slotMs > 0 ? Math.max(0, durMs - slotMs) : 0;

        int n = done.incrementAndGet();
        dubProgress(task, (int) Math.min(76, n * 76.0 / subs.size()));
        return new PlacedClip(idx, clip, s.getStartMs(), durMs, fitted, overflow);
    }

    /** 本行可用槽位（毫秒）：到下一行开口为止；末行到视频结尾；时长未知则不限。 */
    static long slotMs(int idx, List<Subtitle> subs, long videoDurMs) {
        long start = subs.get(idx).getStartMs();
        if (idx + 1 < subs.size()) {
            return subs.get(idx + 1).getStartMs() - start;
        }
        return videoDurMs > start ? videoDurMs - start : Long.MAX_VALUE;
    }

    /** 需要的变速倍率：装得下（含容差）返回 1.0，否则 dur/slot 且不超过 {@link #MAX_TEMPO}。 */
    static double fitTempo(long durMs, long slotMs) {
        if (slotMs <= 0 || durMs <= slotMs + TEMPO_TOLERANCE_MS) {
            return 1.0;
        }
        return Math.min(MAX_TEMPO, durMs / (double) slotMs);
    }

    /** 带重试的单行合成：网络抖动重试，最终失败带上行号给用户可读的错误。 */
    private byte[] synthesizeWithRetry(TtsProvider provider, String text,
                                       TtsConfig cfg, TtsOptions opts, Integer lineSeq) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= SYNTH_ATTEMPTS; attempt++) {
            try {
                byte[] audio = provider.synthesize(text, cfg, opts);
                if (audio != null && audio.length > 0) {
                    return audio;
                }
                last = new BizException("TTS 返回空音频");
            } catch (RuntimeException e) {
                last = e;
            }
            if (attempt < SYNTH_ATTEMPTS) {
                try {
                    Thread.sleep(800L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new BizException("第 " + lineSeq + " 行配音合成失败（已重试 " + SYNTH_ATTEMPTS + " 次）: "
                + (last == null ? "未知错误" : last.getMessage()));
    }

    /**
     * 配音成功时给用户的可读提示：哪些行的译文太长（压到 {@link #MAX_TEMPO} 仍装不下槽位），
     * 会与下一行有短暂重叠。用户可据此改短译文或调低语速；null 表示一切正常。
     */
    private String fitNotice(List<PlacedClip> clips, List<Subtitle> subs) {
        List<PlacedClip> bad = clips.stream()
                .filter(c -> c.overflowMs() > TEMPO_TOLERANCE_MS)
                .sorted((a, b) -> Long.compare(b.overflowMs(), a.overflowMs()))
                .toList();
        if (bad.isEmpty()) {
            return null;
        }
        String seqs = bad.stream().limit(5)
                .map(c -> "第 " + subs.get(c.index()).getSeq() + " 行")
                .collect(java.util.stream.Collectors.joining("、"));
        long worstSec = Math.round(bad.get(0).overflowMs() / 100.0) / 10;
        return String.format(
                "有 %d 行译文偏长，已按最高 %.1f 倍语速压缩，但仍会与下一行重叠（最多约 %.1f 秒）：%s%s。"
                        + "可在字幕编辑器把这些行的译文改短，或降低配音语速后重新配音。",
                bad.size(), MAX_TEMPO, (double) worstSec, seqs,
                bad.size() > 5 ? " 等" : "");
    }

    /** 变速/溢出统计日志：残余溢出说明译文比槽位长得离谱（>1.4x），提示用户可调语速。 */
    private void logFitSummary(List<PlacedClip> clips, long t0) {
        long fitted = clips.stream().filter(PlacedClip::tempoFitted).count();
        List<PlacedClip> overflowed = clips.stream().filter(c -> c.overflowMs() > TEMPO_TOLERANCE_MS).toList();
        long worst = overflowed.stream().mapToLong(PlacedClip::overflowMs).max().orElse(0);
        log.info("配音合成完毕: {} 行耗时 {}s；变速压缩 {} 行（上限 {}x）{}",
                clips.size(), (System.currentTimeMillis() - t0) / 1000, fitted, MAX_TEMPO,
                overflowed.isEmpty() ? "" : String.format("；仍超槽 %d 行（最长溢出 %dms，与下行重叠混音）",
                        overflowed.size(), worst));
    }

    /** 配音进度（0~100）：多线程安全、单调递增，避免并发行完成时进度条回跳。 */
    private void dubProgress(TranslationTask task, int pct) {
        synchronized (task) {
            Integer cur = task.getDubProgress();
            if (cur == null || pct > cur) {
                task.setDubProgress(pct);
                taskMapper.updateById(task);
            }
        }
    }

    /** 配音前校验：仅 DONE 的视频任务、原视频在、有译文字幕、未在配音中。校验通过返回任务。 */
    public TranslationTask requireDubbable(TranslationTask task) {
        // 白名单判定：新增媒体类型（如 TEXT）不会被误放行到 ffmpeg 路径
        if (!MediaKind.VIDEO.name().equals(task.getMediaType())) {
            throw new BizException("该任务不支持配音合成（仅视频任务可配音）");
        }
        if (!TaskStatus.DONE.name().equals(task.getStatus())) {
            throw new BizException("请先等任务完成再配音");
        }
        if ("DUBBING".equals(task.getDubStatus())) {
            throw new BizException("配音正在进行中，请稍候");
        }
        if (task.getVideoPath() == null || !Files.exists(Path.of(task.getVideoPath()))) {
            throw new BizException("原视频文件不存在，无法配音");
        }
        return task;
    }

    /** 取前若干条里第一条足够长的译文作试听样例（照 BurnService.firstSubtitleText）。 */
    private String sampleText(Long taskId) {
        List<Subtitle> subs = subtitleMapper.selectList(
                Wrappers.<Subtitle>lambdaQuery()
                        .eq(Subtitle::getTaskId, taskId)
                        .orderByAsc(Subtitle::getSeq).last("limit 20"));
        String best = null;
        for (Subtitle s : subs) {
            String t = s.getTargetText();
            if (t == null || t.isBlank()) continue;
            t = t.trim();
            if (t.length() >= 6) return t;
            if (best == null) best = t;
        }
        if (best == null) {
            throw new BizException("没有可试听的译文字幕");
        }
        return best;
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
