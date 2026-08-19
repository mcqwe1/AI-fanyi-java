package com.aifanyi.media;

import com.aifanyi.common.BizException;
import com.aifanyi.config.AifanyiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FFmpeg 命令行封装：抽音频、探测分辨率/时长、烧录 ASS 字幕（带进度）、抽帧预览。
 * 烧录/预览以视频所在目录为工作目录、用相对文件名，避开 Windows 盘符冒号转义问题。
 */
@Slf4j
@Service
public class FfmpegService {

    private static final Pattern TIME_PATTERN =
            Pattern.compile("time=(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");

    private final String ffmpeg;
    private final String ffprobe;

    public FfmpegService(AifanyiProperties props) {
        this.ffmpeg = props.getFfmpeg().getPath();
        this.ffprobe = deriveFfprobe(this.ffmpeg);
    }

    private static String deriveFfprobe(String ffmpegPath) {
        if (ffmpegPath.endsWith("ffmpeg.exe")) {
            return ffmpegPath.substring(0, ffmpegPath.length() - "ffmpeg.exe".length()) + "ffprobe.exe";
        }
        if (ffmpegPath.endsWith("ffmpeg")) {
            return ffmpegPath.substring(0, ffmpegPath.length() - "ffmpeg".length()) + "ffprobe";
        }
        return "ffprobe";
    }

    /** 从视频抽取音频为 16kHz 单声道 mp3（体积小、适合 ASR）。 */
    public Path extractAudio(Path video, Path outAudio) {
        List<String> cmd = List.of(
                ffmpeg, "-y",
                "-i", video.toString(),
                "-vn", "-ac", "1", "-ar", "16000", "-b:a", "64k",
                outAudio.toString()
        );
        run(cmd, null, 30, TimeUnit.MINUTES, null);
        return outAudio;
    }

    /** 从音频切出 [startSec, startSec+durSec) 一段，重编码为 16kHz 单声道 mp3（用于超大文件分块转写）。 */
    public Path cutAudio(Path inAudio, Path outAudio, double startSec, double durSec) {
        List<String> cmd = List.of(
                ffmpeg, "-y",
                "-ss", String.format(java.util.Locale.ROOT, "%.3f", Math.max(0, startSec)),
                "-t", String.format(java.util.Locale.ROOT, "%.3f", durSec),
                "-i", inAudio.toString(),
                "-ac", "1", "-ar", "16000", "-b:a", "64k",
                outAudio.toString()
        );
        run(cmd, null, 30, TimeUnit.MINUTES, null);
        return outAudio;
    }

    /** 探测视频分辨率，返回 [width, height]，失败回退 1280x720。 */
    public int[] probeResolution(Path video) {
        List<String> cmd = List.of(
                ffprobe, "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=width,height",
                "-of", "csv=p=0:s=x", video.toString()
        );
        String out = runCapture(cmd, 30, TimeUnit.SECONDS).trim();
        try {
            String[] wh = out.split("x");
            int w = Integer.parseInt(wh[0].trim());
            int h = Integer.parseInt(wh[1].trim());
            if (w > 0 && h > 0) return new int[]{w, h};
        } catch (Exception e) {
            log.warn("解析分辨率失败: '{}'", out);
        }
        return new int[]{1280, 720};
    }

    /** 探测视频时长（秒），失败返回 0。 */
    public double probeDurationSec(Path video) {
        List<String> cmd = List.of(
                ffprobe, "-v", "error", "-show_entries", "format=duration",
                "-of", "csv=p=0", video.toString()
        );
        String out = runCapture(cmd, 30, TimeUnit.SECONDS).trim();
        try {
            return Double.parseDouble(out.split("\\s+")[0]);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 探测音频流相对视频流的起始偏移（毫秒）= audio.start_time - video.start_time。
     * 抽出的纯音频文件丢失了容器里的这一偏移，ASR 时间戳需整体加回该值，
     * 否则字幕相对视频恒定提前/滞后（部分 TS/带 edit list 的 MP4 会有几百毫秒到秒级偏移）。
     * 任一流缺失或 start_time 不可用（N/A）按 0；|offset|<50ms 视为无偏移；
     * >30s 视为容器脏数据，忽略并告警。
     */
    public long probeAvStartOffsetMs(Path video) {
        Double a = probeStreamStartSec(video, "a:0");
        Double v = probeStreamStartSec(video, "v:0");
        if (a == null || v == null) {
            return 0;
        }
        long off = Math.round((a - v) * 1000);
        if (Math.abs(off) < 50) {
            return 0;
        }
        if (Math.abs(off) > 30_000) {
            log.warn("音视频流起始偏移异常({}ms)，疑似容器脏数据，忽略: {}", off, video.getFileName());
            return 0;
        }
        log.info("检测到音视频流起始偏移 {}ms（audio {}s / video {}s），字幕轴将整体平移", off, a, v);
        return off;
    }

    /** 探测单个流的 start_time（秒）；流缺失或值为 N/A 返回 null。 */
    private Double probeStreamStartSec(Path video, String streamSelector) {
        List<String> cmd = List.of(
                ffprobe, "-v", "error", "-select_streams", streamSelector,
                "-show_entries", "stream=start_time",
                "-of", "csv=p=0", video.toString()
        );
        String out = runCapture(cmd, 30, TimeUnit.SECONDS).trim();
        try {
            return Double.parseDouble(out.split("\\s+")[0]);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 用 silencedetect 检测静音区间，返回 [startMs, endMs] 列表。
     * 用于丢弃 Whisper 在静音处的幻觉字幕。
     *
     * @param noiseDb   静音判定阈值（如 -50 表示 -50dB；越低越保守、只判真静音）
     * @param minDurSec 最短静音时长（秒）
     */
    public List<long[]> detectSilenceMs(Path audio, int noiseDb, double minDurSec) {
        List<String> cmd = List.of(
                ffmpeg, "-i", audio.toString(),
                "-af", "silencedetect=noise=" + noiseDb + "dB:d=" + minDurSec,
                "-f", "null", "-"
        );
        String out = runCapture(cmd, 30, TimeUnit.MINUTES);
        List<Double> starts = new ArrayList<>();
        List<Double> ends = new ArrayList<>();
        Matcher ms = Pattern.compile("silence_start:\\s*([0-9.]+)").matcher(out);
        while (ms.find()) starts.add(Double.parseDouble(ms.group(1)));
        Matcher me = Pattern.compile("silence_end:\\s*([0-9.]+)").matcher(out);
        while (me.find()) ends.add(Double.parseDouble(me.group(1)));

        List<long[]> intervals = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            long s = Math.round(starts.get(i) * 1000);
            long e = (i < ends.size()) ? Math.round(ends.get(i) * 1000) : Long.MAX_VALUE;
            intervals.add(new long[]{s, e});
        }
        return intervals;
    }

    /**
     * 把 ASS 字幕烧录进视频（重编码），通过解析 ffmpeg 输出回报进度百分比。
     *
     * <p>视频编码器优先用显卡（见 {@link #videoEncoderArgs()}）：实测 16.8 分钟 720p，
     * libx264 veryfast 要 71.8s / 107.7MB，h264_nvenc p5 cq28 只要 36.0s / 99.9MB——
     * 快一倍，文件还小一点。探测不到硬件编码器时自动回退 libx264，行为与改造前完全一致。
     *
     * @param totalSec   视频总时长（秒），用于计算百分比；<=0 则不报进度
     * @param onProgress 进度回调（0~99），可为 null
     */
    public Path burnAss(Path video, Path assFile, Path output, double totalSec, IntConsumer onProgress) {
        Path dir = video.getParent();
        List<String> cmd = new ArrayList<>(List.of(
                ffmpeg, "-y",
                "-i", video.getFileName().toString(),
                "-vf", "ass=" + assFile.getFileName().toString()));
        cmd.addAll(videoEncoderArgs());
        cmd.addAll(List.of("-c:a", "aac", "-b:a", "128k", output.getFileName().toString()));
        final int[] last = {0};
        Consumer<String> onLine = (onProgress == null || totalSec <= 0) ? null : line -> {
            Matcher m = TIME_PATTERN.matcher(line);
            if (m.find()) {
                double sec = Integer.parseInt(m.group(1)) * 3600
                        + Integer.parseInt(m.group(2)) * 60
                        + Double.parseDouble(m.group(3));
                int pct = (int) Math.min(99, sec / totalSec * 100);
                if (pct >= last[0] + 2) {
                    last[0] = pct;
                    onProgress.accept(pct);
                }
            }
        };
        run(cmd, dir, 120, TimeUnit.MINUTES, onLine);
        return output;
    }

    // ---------------- 视频编码器选择（烧录用） ----------------

    /**
     * 硬件编码器候选，按优先级排列：N 卡 → Intel 核显 → A 卡。
     * <p>质量参数都调到「文件大小与 libx264 veryfast crf20 相当或更小」那一档——
     * 硬件编码器同码率下画质略逊于 x264，一味求快会让用户觉得"烧完糊了"。
     */
    private static final List<String[]> HW_ENCODERS = List.of(
            new String[]{"h264_nvenc", "-c:v", "h264_nvenc", "-preset", "p5", "-rc", "vbr", "-cq", "28", "-b:v", "0"},
            new String[]{"h264_qsv", "-c:v", "h264_qsv", "-preset", "medium", "-global_quality", "26"},
            new String[]{"h264_amf", "-c:v", "h264_amf", "-quality", "balanced", "-rc", "cqp", "-qp_i", "26", "-qp_p", "28"});

    /** 软件回退：与改造前逐字一致。 */
    private static final List<String> SW_ENCODER =
            List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "20");

    /** 探测结果缓存：机器的编码器能力一次开机内不会变，没必要每次烧录都问一遍 ffmpeg。 */
    private volatile List<String> cachedEncoderArgs;

    /**
     * 本机该用哪个视频编码器。首次调用问一次 {@code ffmpeg -encoders} 并缓存。
     * <p>可用环境变量 {@code BURN_VIDEO_ENCODER=libx264} 强制走软件编码——
     * 显卡驱动有问题、或烧录要与本机上的转写抢显存时，用户得有个退路。
     */
    List<String> videoEncoderArgs() {
        List<String> cached = cachedEncoderArgs;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedEncoderArgs != null) {
                return cachedEncoderArgs;
            }
            String forced = System.getenv("BURN_VIDEO_ENCODER");
            if (forced != null && !forced.isBlank()) {
                if ("libx264".equalsIgnoreCase(forced.trim())) {
                    log.info("烧录编码器：按 BURN_VIDEO_ENCODER 强制使用 libx264（软件编码）");
                    cachedEncoderArgs = SW_ENCODER;
                    return cachedEncoderArgs;
                }
                log.warn("BURN_VIDEO_ENCODER={} 不认识，忽略，按自动探测处理", forced);
            }
            String available;
            try {
                available = runCapture(List.of(ffmpeg, "-hide_banner", "-encoders"), 20, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("探测 ffmpeg 编码器失败，用软件编码: {}", e.getMessage());
                cachedEncoderArgs = SW_ENCODER;
                return cachedEncoderArgs;
            }
            for (String[] cand : HW_ENCODERS) {
                if (available.contains(cand[0])) {
                    cachedEncoderArgs = List.of(cand).subList(1, cand.length);
                    log.info("烧录编码器：{}（显卡编码，实测比 libx264 veryfast 快约一倍）", cand[0]);
                    return cachedEncoderArgs;
                }
            }
            log.info("烧录编码器：libx264（未探测到可用的显卡编码器）");
            cachedEncoderArgs = SW_ENCODER;
            return cachedEncoderArgs;
        }
    }

    /** 抽取一帧并烧上样例字幕，生成预览图。video 与 assFile 同目录。seek 形如 "00:00:02"。 */
    public Path previewFrame(Path video, Path assFile, Path outJpg, String seek) {
        Path dir = video.getParent();
        List<String> cmd = List.of(
                ffmpeg, "-y", "-ss", seek,
                "-i", video.getFileName().toString(),
                "-vf", "ass=" + assFile.getFileName().toString(),
                "-frames:v", "1", "-update", "1", "-q:v", "2",
                outJpg.getFileName().toString()
        );
        run(cmd, dir, 2, TimeUnit.MINUTES, null);
        return outJpg;
    }

    /** 视频封面：seekSec 处抽一帧，等比缩到宽 480（工作台「最近项目」卡片用）。 */
    public Path coverFrame(Path video, Path outJpg, double seekSec) {
        List<String> cmd = List.of(
                ffmpeg, "-y",
                "-ss", String.format(java.util.Locale.ROOT, "%.2f", Math.max(0, seekSec)),
                "-i", video.toString(),
                "-frames:v", "1", "-update", "1",
                "-vf", "scale=480:-2",
                "-q:v", "4",
                outJpg.toString()
        );
        run(cmd, null, 2, TimeUnit.MINUTES, null);
        return outJpg;
    }

    /** 音频封面：showwavespic 波形图（480x160 PNG，透明底），前端叠自己的底色。 */
    public Path coverWaveform(Path audio, Path outPng) {
        List<String> cmd = List.of(
                ffmpeg, "-y",
                "-i", audio.toString(),
                "-filter_complex", "aformat=channel_layouts=mono,showwavespic=s=480x160:colors=#7c9cff",
                "-frames:v", "1", "-update", "1",
                outPng.toString()
        );
        run(cmd, null, 2, TimeUnit.MINUTES, null);
        return outPng;
    }

    // ---- TTS 配音：片段解码与变速 ----
    // 拼轨在 WavTrackAssembler 里纯 Java 完成；ffmpeg 只负责把 TTS 返回的音频
    // 解码成统一规格的 PCM WAV（时长采样级精确，无 mp3 帧量化/编码器延迟漂移）。

    /**
     * 把 TTS 返回的音频解码为 44.1kHz 单声道 16bit PCM WAV（配音拼轨统一中间格式），
     * 并裁掉首尾静音留白。
     * TTS 端点（edge-tts 实测首 ~180ms / 尾 ~590ms）在语音前后附带的静音，若原样拼进轨道：
     *  ① 首部留白 = 配音比字幕晚出的固定偏差；② 尾部留白白占槽位，逼出不必要的变速压缩。
     * 裁剪阈值 -50dB 与 detectSilenceMs 一致（只削真静音，气声/轻声不受影响）。
     */
    public Path decodeToWavMono44k(Path in, Path out) {
        List<String> cmd = List.of(
                ffmpeg, "-y",
                "-i", in.toString(),
                "-af", TRIM_SILENCE_FILTER,
                "-ac", "1", "-ar", "44100", "-c:a", "pcm_s16le",
                out.toString()
        );
        run(cmd, null, 5, TimeUnit.MINUTES, null);
        return out;
    }

    /** 掐头去尾的静音裁剪：正向削首部，areverse 反转后再削一次即削掉尾部，再转回来。 */
    private static final String TRIM_SILENCE_FILTER =
            "silenceremove=start_periods=1:start_silence=0:start_threshold=-50dB,"
                    + "areverse,"
                    + "silenceremove=start_periods=1:start_silence=0:start_threshold=-50dB,"
                    + "areverse";

    /** WAV 变速（atempo 保音高不变调），用于把超出字幕槽位的配音行压回槽内。 */
    public Path atempoWav(Path in, Path out, double tempo) {
        List<String> cmd = List.of(
                ffmpeg, "-y",
                "-i", in.toString(),
                "-af", "atempo=" + String.format(java.util.Locale.ROOT, "%.4f", tempo),
                "-ac", "1", "-ar", "44100", "-c:a", "pcm_s16le",
                out.toString()
        );
        run(cmd, null, 5, TimeUnit.MINUTES, null);
        return out;
    }

    /** 视频是否含音频流（决定「保留原声」是否可用）。 */
    public boolean probeHasAudio(Path video) {
        List<String> cmd = List.of(
                ffprobe, "-v", "error", "-select_streams", "a:0",
                "-show_entries", "stream=codec_type",
                "-of", "csv=p=0", video.toString()
        );
        return runCapture(cmd, 30, TimeUnit.SECONDS).trim().contains("audio");
    }

    /**
     * 把配音轨混入视频（视频流直拷不重编码）。
     * keepOriginalBg=false：配音直接替换原声；true：原声压到 15% 音量做背景与配音混合。
     *
     * @param totalSec   视频总时长（秒），用于进度；<=0 不报进度
     * @param onProgress 进度回调（0~99），可为 null
     */
    public Path muxAudioIntoVideo(Path video, Path dubAudio, Path out,
                                  boolean keepOriginalBg, double totalSec, IntConsumer onProgress) {
        List<String> cmd;
        if (keepOriginalBg) {
            cmd = List.of(
                    ffmpeg, "-y",
                    "-i", video.toString(),
                    "-i", dubAudio.toString(),
                    "-filter_complex",
                    "[0:a]volume=0.15[bg];[1:a][bg]amix=inputs=2:duration=first:dropout_transition=0[mix]",
                    "-map", "0:v", "-map", "[mix]",
                    "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
                    "-shortest",
                    out.toString()
            );
        } else {
            cmd = List.of(
                    ffmpeg, "-y",
                    "-i", video.toString(),
                    "-i", dubAudio.toString(),
                    "-map", "0:v", "-map", "1:a",
                    "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
                    "-shortest",
                    out.toString()
            );
        }
        final int[] last = {0};
        Consumer<String> onLine = (onProgress == null || totalSec <= 0) ? null : line -> {
            Matcher m = TIME_PATTERN.matcher(line);
            if (m.find()) {
                double sec = Integer.parseInt(m.group(1)) * 3600
                        + Integer.parseInt(m.group(2)) * 60
                        + Double.parseDouble(m.group(3));
                int pct = (int) Math.min(99, sec / totalSec * 100);
                if (pct >= last[0] + 2) {
                    last[0] = pct;
                    onProgress.accept(pct);
                }
            }
        };
        run(cmd, null, 120, TimeUnit.MINUTES, onLine);
        return out;
    }

    // ---- 内部执行 ----

    private void run(List<String> cmd, Path workDir, long timeout, TimeUnit unit, Consumer<String> onLine) {
        log.info("执行: {} (cwd={})", String.join(" ", cmd), workDir);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        try {
            Process p = pb.start();
            List<String> tail = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    tail.add(line);
                    if (tail.size() > 30) tail.remove(0);
                    if (onLine != null) onLine.accept(line);
                }
            }
            if (!p.waitFor(timeout, unit)) {
                p.destroyForcibly();
                throw new BizException("FFmpeg 执行超时");
            }
            if (p.exitValue() != 0) {
                throw new BizException("FFmpeg 失败(code=" + p.exitValue() + "): " + String.join("\n", tail));
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("FFmpeg 调用异常: " + e.getMessage());
        }
    }

    private String runCapture(List<String> cmd, long timeout, TimeUnit unit) {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        try {
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            if (!p.waitFor(timeout, unit)) {
                p.destroyForcibly();
                throw new BizException("ffprobe 超时");
            }
            return sb.toString();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("ffprobe 调用异常: " + e.getMessage());
        }
    }
}
