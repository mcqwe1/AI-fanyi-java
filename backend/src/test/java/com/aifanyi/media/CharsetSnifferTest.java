package com.aifanyi.media;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CharsetSnifferTest {

    @Test
    void utf8NoBomDecoded() {
        byte[] b = "你好世界 hello".getBytes(StandardCharsets.UTF_8);
        assertThat(CharsetSniffer.decode(b)).isEqualTo("你好世界 hello");
    }

    @Test
    void utf8BomStripped() {
        byte[] body = "中文".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        assertThat(CharsetSniffer.decode(withBom)).isEqualTo("中文");
    }

    @Test
    void gbkFallbackForNonUtf8() {
        // GBK 编码的中文在严格 UTF-8 校验下会失败 → 回退 GBK 正确解码
        byte[] gbk = "字幕测试".getBytes(Charset.forName("GBK"));
        assertThat(CharsetSniffer.decode(gbk)).isEqualTo("字幕测试");
    }

    @Test
    void asciiUnaffected() {
        assertThat(CharsetSniffer.decode("plain ascii".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("plain ascii");
    }

    @Test
    void emptyIsEmpty() {
        assertThat(CharsetSniffer.decode(new byte[0])).isEmpty();
    }
}
