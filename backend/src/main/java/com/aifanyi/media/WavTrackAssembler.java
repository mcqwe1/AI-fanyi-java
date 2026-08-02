package com.aifanyi.media;

import com.aifanyi.common.BizException;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * 配音轨拼装：把若干 44.1kHz/16bit/单声道 WAV 片段按各自 startMs 写到整条轨道的
 * 绝对采样位置上，空隙天然为静音，重叠区做饱和混音。
 * 与旧的「静音片段 + concat 传送带」方案的本质区别：每段的位置只由自己的 startMs 决定，
 * 某段超长只会与下一段重叠，绝不会把后续所有段向后推——时间轴误差不随行数累积。
 * 纯 Java 采样级写入，无 mp3 帧量化/编码器延迟漂移，也不需要为静音/拼接起任何 ffmpeg 进程。
 */
public final class WavTrackAssembler {

    public static final int SAMPLE_RATE = 44100;
    private static final int BYTES_PER_SAMPLE = 2;      // 16-bit
    private static final int HEADER_LEN = 44;           // 标准 PCM WAV 头

    private WavTrackAssembler() {
    }

    /** 一个待放置的片段：wav 文件 + 它在整条轨道上的起点。 */
    public record Clip(Path wav, long startMs) {
    }

    /** WAV 内 data 块的定位与规格校验结果。 */
    private record DataChunk(long offset, long length) {
    }

    /** 片段实际时长（毫秒，按 data 块采样数精确计算）。 */
    public static long durationMs(Path wav) {
        try (RandomAccessFile raf = new RandomAccessFile(wav.toFile(), "r")) {
            DataChunk data = locateData(raf, wav);
            return Math.round(data.length() / (double) BYTES_PER_SAMPLE * 1000.0 / SAMPLE_RATE);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException("读取 WAV 失败 " + wav.getFileName() + ": " + e.getMessage());
        }
    }

    /**
     * 拼装整条配音轨。
     *
     * @param clips   片段列表（startMs 可乱序、可重叠）
     * @param totalMs 轨道总时长（一般 = 视频时长与最晚片段终点的较大者）
     * @param outWav  输出 WAV（44.1kHz/16bit/mono）
     */
    public static void assemble(List<Clip> clips, long totalMs, Path outWav) {
        long totalSamples = msToSample(totalMs);
        long dataLen = totalSamples * BYTES_PER_SAMPLE;
        if (dataLen + HEADER_LEN > Integer.MAX_VALUE - 1024) {
            // RIFF 尺寸字段是 32 位；44.1kHz 单声道下限约 6.7 小时，正常视频不可能触达
            throw new BizException("配音轨过长（" + totalMs / 60000 + " 分钟），超出 WAV 容量上限");
        }
        try (RandomAccessFile out = new RandomAccessFile(outWav.toFile(), "rw")) {
            out.setLength(0);
            out.write(buildHeader((int) dataLen));
            // 先把文件扩展到完整长度：未写区域读出即为 0（静音）
            out.setLength(HEADER_LEN + dataLen);

            for (Clip clip : clips) {
                byte[] pcm = readPcm(clip.wav());
                long startByte = HEADER_LEN + msToSample(clip.startMs()) * BYTES_PER_SAMPLE;
                long room = HEADER_LEN + dataLen - startByte;
                if (room <= 0) {
                    continue;                       // 起点已在轨道末尾之外（理论不该发生）
                }
                int writeLen = (int) Math.min(pcm.length, room);
                byte[] existing = new byte[writeLen];
                out.seek(startByte);
                out.readFully(existing);
                mixInto(existing, pcm, writeLen);
                out.seek(startByte);
                out.write(existing, 0, writeLen);
            }
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException("写配音轨失败: " + e.getMessage());
        }
    }

    /** 饱和混音：逐样本相加，钳制到 16 位范围（重叠区通常极短，直接相加听感自然）。 */
    private static void mixInto(byte[] existing, byte[] add, int len) {
        for (int i = 0; i + 1 < len; i += 2) {
            int a = (short) ((existing[i] & 0xFF) | (existing[i + 1] << 8));
            int b = (short) ((add[i] & 0xFF) | (add[i + 1] << 8));
            int mixed = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, a + b));
            existing[i] = (byte) mixed;
            existing[i + 1] = (byte) (mixed >> 8);
        }
    }

    private static long msToSample(long ms) {
        return Math.round(ms * (double) SAMPLE_RATE / 1000.0);
    }

    /** 读出片段的全部 PCM 数据（片段是几秒级的短音频，整块读入没有内存压力）。 */
    private static byte[] readPcm(Path wav) {
        try (RandomAccessFile raf = new RandomAccessFile(wav.toFile(), "r")) {
            DataChunk data = locateData(raf, wav);
            if (data.length() > 512L * 1024 * 1024) {
                throw new BizException("配音片段异常过大: " + wav.getFileName());
            }
            byte[] pcm = new byte[(int) data.length()];
            raf.seek(data.offset());
            raf.readFully(pcm);
            return pcm;
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException("读取 WAV 失败 " + wav.getFileName() + ": " + e.getMessage());
        }
    }

    /**
     * 走一遍 RIFF 块结构找 data 块（ffmpeg 输出可能在 fmt 与 data 之间夹 LIST 等块），
     * 顺路校验 fmt 是 PCM/单声道/44.1kHz/16bit——上游统一用
     * {@code ffmpeg -ac 1 -ar 44100 -c:a pcm_s16le} 产出，规格不符说明上游改了参数。
     */
    private static DataChunk locateData(RandomAccessFile raf, Path wav) throws IOException {
        byte[] four = new byte[4];
        raf.seek(0);
        raf.readFully(four);
        if (!"RIFF".equals(new String(four, StandardCharsets.US_ASCII))) {
            throw new BizException("不是 WAV 文件: " + wav.getFileName());
        }
        raf.skipBytes(4);                           // RIFF 总长
        raf.readFully(four);
        if (!"WAVE".equals(new String(four, StandardCharsets.US_ASCII))) {
            throw new BizException("不是 WAV 文件: " + wav.getFileName());
        }
        boolean fmtChecked = false;
        long fileLen = raf.length();
        while (raf.getFilePointer() + 8 <= fileLen) {
            raf.readFully(four);
            String id = new String(four, StandardCharsets.US_ASCII);
            long size = readLeUint32(raf);
            long body = raf.getFilePointer();
            if ("fmt ".equals(id)) {
                byte[] fmt = new byte[16];
                raf.readFully(fmt);
                ByteBuffer bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN);
                int audioFormat = bb.getShort() & 0xFFFF;
                int channels = bb.getShort() & 0xFFFF;
                int sampleRate = bb.getInt();
                bb.getInt();                        // byteRate
                bb.getShort();                      // blockAlign
                int bits = bb.getShort() & 0xFFFF;
                if (audioFormat != 1 || channels != 1 || sampleRate != SAMPLE_RATE || bits != 16) {
                    throw new BizException(String.format(
                            "WAV 规格不符（fmt=%d ch=%d rate=%d bits=%d，要求 PCM/mono/44100/16）: %s",
                            audioFormat, channels, sampleRate, bits, wav.getFileName()));
                }
                fmtChecked = true;
            } else if ("data".equals(id)) {
                if (!fmtChecked) {
                    throw new BizException("WAV 缺少 fmt 块: " + wav.getFileName());
                }
                long avail = fileLen - body;
                return new DataChunk(body, Math.min(size, avail));
            }
            // 跳到下一块（RIFF 块按 2 字节对齐）
            raf.seek(body + size + (size % 2));
        }
        throw new EOFException("WAV 缺少 data 块: " + wav.getFileName());
    }

    private static long readLeUint32(RandomAccessFile raf) throws IOException {
        byte[] b = new byte[4];
        raf.readFully(b);
        return (b[0] & 0xFFL) | (b[1] & 0xFFL) << 8 | (b[2] & 0xFFL) << 16 | (b[3] & 0xFFL) << 24;
    }

    /** 44 字节标准 PCM WAV 头。 */
    private static byte[] buildHeader(int dataLen) {
        ByteBuffer bb = ByteBuffer.allocate(HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(36 + dataLen);
        bb.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        bb.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(16);
        bb.putShort((short) 1);                     // PCM
        bb.putShort((short) 1);                     // mono
        bb.putInt(SAMPLE_RATE);
        bb.putInt(SAMPLE_RATE * BYTES_PER_SAMPLE);  // byteRate
        bb.putShort((short) BYTES_PER_SAMPLE);      // blockAlign
        bb.putShort((short) 16);                    // bits
        bb.put("data".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(dataLen);
        return bb.array();
    }
}
