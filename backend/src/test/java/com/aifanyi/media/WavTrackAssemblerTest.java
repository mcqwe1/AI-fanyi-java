package com.aifanyi.media;

import com.aifanyi.common.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WavTrackAssembler 单测：采样级定位、空隙静音、重叠饱和混音、RIFF 杂项块跳过、规格校验。
 */
class WavTrackAssemblerTest {

    @TempDir
    Path dir;

    private static final int SR = WavTrackAssembler.SAMPLE_RATE;

    // ---- 帮助函数：生成测试 WAV ----

    private static byte[] header(int dataLen, int channels, int sampleRate, int bits) {
        ByteBuffer bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataLen)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) channels).putInt(sampleRate)
                .putInt(sampleRate * channels * bits / 8)
                .putShort((short) (channels * bits / 8)).putShort((short) bits)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataLen);
        return bb.array();
    }

    /** 写一个 durMs 长、全部样本为 value 的标准 WAV。 */
    private Path constWav(String name, long durMs, short value) throws IOException {
        int samples = (int) Math.round(durMs * SR / 1000.0);
        ByteBuffer bb = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(header(samples * 2, 1, SR, 16));
        for (int i = 0; i < samples; i++) {
            bb.putShort(value);
        }
        Path p = dir.resolve(name);
        Files.write(p, bb.array());
        return p;
    }

    /** 带 LIST 杂项块（ffmpeg 常见）的 WAV：fmt 与 data 之间插入无关块。 */
    private Path wavWithJunkChunk(String name, int samples, short value) throws IOException {
        byte[] junkBody = "INFOISFT".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer bb = ByteBuffer.allocate(44 + 8 + junkBody.length + samples * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + 8 + junkBody.length + samples * 2)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(SR)
                .putInt(SR * 2).putShort((short) 2).putShort((short) 16)
                .put("LIST".getBytes(StandardCharsets.US_ASCII)).putInt(junkBody.length).put(junkBody)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples * 2);
        for (int i = 0; i < samples; i++) {
            bb.putShort(value);
        }
        Path p = dir.resolve(name);
        Files.write(p, bb.array());
        return p;
    }

    /** 读拼装结果在 ms 时刻的样本值（输出恒为 44 字节头标准 PCM）。 */
    private static short sampleAt(Path wav, long ms) throws IOException {
        long idx = Math.round(ms * SR / 1000.0);
        try (RandomAccessFile raf = new RandomAccessFile(wav.toFile(), "r")) {
            raf.seek(44 + idx * 2);
            int lo = raf.read();
            int hi = raf.read();
            return (short) ((lo & 0xFF) | (hi << 8));
        }
    }

    // ---- durationMs ----

    @Test
    void durationExactFromSampleCount() throws IOException {
        Path p = constWav("one_sec.wav", 1000, (short) 100);
        assertEquals(1000, WavTrackAssembler.durationMs(p));
        // 250ms → 11025 样本，往返换算无损
        assertEquals(250, WavTrackAssembler.durationMs(constWav("q.wav", 250, (short) 1)));
    }

    @Test
    void durationSkipsJunkChunks() throws IOException {
        Path p = wavWithJunkChunk("junk.wav", SR / 2, (short) 5);
        assertEquals(500, WavTrackAssembler.durationMs(p));
    }

    // ---- assemble：绝对定位与静音空隙 ----

    @Test
    void clipsPlacedAtAbsolutePositionsWithSilenceGaps() throws IOException {
        Path a = constWav("a.wav", 100, (short) 1000);
        Path b = constWav("b.wav", 100, (short) -2000);
        Path out = dir.resolve("track.wav");
        WavTrackAssembler.assemble(List.of(
                new WavTrackAssembler.Clip(a, 0),
                new WavTrackAssembler.Clip(b, 200)
        ), 400, out);

        assertEquals(400, WavTrackAssembler.durationMs(out));
        assertEquals(1000, sampleAt(out, 50));    // A 段内
        assertEquals(0, sampleAt(out, 150));      // 空隙 = 静音
        assertEquals(-2000, sampleAt(out, 250));  // B 段内（绝对位置，不受 A 影响）
        assertEquals(0, sampleAt(out, 350));      // 尾部静音
    }

    /** 关键回归：前一段超长时，后一段仍钉在自己的 startMs，不被向后推。 */
    @Test
    void overlongClipDoesNotShiftNextClip() throws IOException {
        Path longA = constWav("la.wav", 300, (short) 1000);   // 槽位只有 100ms，超长 200ms
        Path b = constWav("b2.wav", 100, (short) 2000);
        Path out = dir.resolve("track2.wav");
        WavTrackAssembler.assemble(List.of(
                new WavTrackAssembler.Clip(longA, 0),
                new WavTrackAssembler.Clip(b, 100)
        ), 500, out);

        assertEquals(1000, sampleAt(out, 50));    // A 独占区
        assertEquals(3000, sampleAt(out, 150));   // B 钉在 100ms：与 A 重叠混音 1000+2000
        assertEquals(1000, sampleAt(out, 250));   // B 于 200ms 结束，只剩 A 的尾巴
        // 旧传送带方案会把 B 顺延到 A 结束后（300~400ms）；新方案该区间必须是静音
        assertEquals(0, sampleAt(out, 350));
        assertEquals(0, sampleAt(out, 450));
    }

    @Test
    void overlapMixSaturatesInsteadOfWrapping() throws IOException {
        Path a = constWav("s1.wav", 100, (short) 30000);
        Path b = constWav("s2.wav", 100, (short) 30000);
        Path out = dir.resolve("track3.wav");
        WavTrackAssembler.assemble(List.of(
                new WavTrackAssembler.Clip(a, 0),
                new WavTrackAssembler.Clip(b, 0)
        ), 200, out);
        assertEquals(Short.MAX_VALUE, sampleAt(out, 50)); // 钳制而非溢出反相
    }

    @Test
    void clipBeyondTrackEndIsClamped() throws IOException {
        Path a = constWav("tail.wav", 200, (short) 700);
        Path out = dir.resolve("track4.wav");
        // 轨道 300ms，片段 250ms 起 → 只能写入 50ms，不越界不报错
        WavTrackAssembler.assemble(List.of(new WavTrackAssembler.Clip(a, 250)), 300, out);
        assertEquals(300, WavTrackAssembler.durationMs(out));
        assertEquals(700, sampleAt(out, 270));
    }

    // ---- 规格校验 ----

    @Test
    void rejectsWrongSpecWav() throws IOException {
        int samples = 100;
        ByteBuffer bb = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(header(samples * 2, 2, SR, 16));       // 双声道 → 不符
        for (int i = 0; i < samples; i++) bb.putShort((short) 1);
        Path p = dir.resolve("stereo.wav");
        Files.write(p, bb.array());
        assertThrows(BizException.class, () -> WavTrackAssembler.durationMs(p));
    }

    @Test
    void rejectsNonWavFile() throws IOException {
        Path p = dir.resolve("not.wav");
        Files.write(p, "hello world, definitely not riff".getBytes(StandardCharsets.US_ASCII));
        assertThrows(BizException.class, () -> WavTrackAssembler.durationMs(p));
    }
}
