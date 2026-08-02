package com.aifanyi.media;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 上传文本文件的字符集嗅探：优先 BOM，其次尝试严格 UTF-8 解码，失败回退 GBK
 * （中文 Windows 记事本/字幕最常见的两种编码）。不追求覆盖所有编码，只解决中文场景。
 */
public final class CharsetSniffer {

    private static final Charset GBK = Charset.forName("GBK");

    private CharsetSniffer() {
    }

    /** 按嗅探到的字符集把字节解码为字符串，并去掉可能的 BOM。 */
    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        // BOM 判定
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        // 无 BOM：严格 UTF-8 试解，遇非法字节即判为非 UTF-8 → 回退 GBK
        if (isValidUtf8(bytes)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return new String(bytes, GBK);
    }

    /** 严格校验字节序列是否为合法 UTF-8（不容错，任何非法多字节序列都返回 false）。 */
    private static boolean isValidUtf8(byte[] b) {
        int i = 0;
        while (i < b.length) {
            int c = b[i] & 0xFF;
            int n;
            if (c <= 0x7F) {
                n = 0;
            } else if (c >= 0xC2 && c <= 0xDF) {
                n = 1;
            } else if (c >= 0xE0 && c <= 0xEF) {
                n = 2;
            } else if (c >= 0xF0 && c <= 0xF4) {
                n = 3;
            } else {
                return false;                 // 非法起始字节
            }
            if (i + n >= b.length && n > 0 && i + n >= b.length) {
                // 需要 n 个后续字节
            }
            for (int j = 0; j < n; j++) {
                if (i + 1 + j >= b.length) {
                    return false;             // 截断的多字节序列
                }
                int cc = b[i + 1 + j] & 0xFF;
                if (cc < 0x80 || cc > 0xBF) {
                    return false;             // 后续字节必须是 10xxxxxx
                }
            }
            i += n + 1;
        }
        return true;
    }
}
