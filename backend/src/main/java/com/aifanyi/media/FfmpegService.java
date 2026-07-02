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
     * @param totalSec   视频总时长（秒），用于计算百分比；<=0 则不报进度
     * @param onProgress 进度回调（0~99），可为 null
     */
    public Path burnAss(Path video, Path assFile, Path output, double totalSec, IntConsumer onProgress) {
        Path dir = video.getParent();
        List<String> cmd = List.of(
                ffmpeg, "-y",
                "-i", video.getFileName().toString(),
                "-vf", "ass=" + assFile.getFileName().toString(),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                "-c:a", "aac", "-b:a", "128k",
                output.getFileName().toString()
        );
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
