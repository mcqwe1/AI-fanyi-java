package com.aifanyi.media;

import com.aifanyi.asr.Segment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「已有字幕直翻」的入口解析器。用户的字幕文件来源杂（下载的、剪辑软件导的、手写的），
 * 这里的每条用例都对应一种真实见过的写法——解析错一条，用户的整条字幕就错位。
 */
class SubtitleParserTest {

    @Test
    void 标准srt三条() {
        String srt = """
                1
                00:00:01,000 --> 00:00:03,500
                Hello world.

                2
                00:00:04,000 --> 00:00:06,000
                Second line.

                3
                00:01:00,250 --> 00:01:02,000
                Third line.
                """;
        List<Segment> segs = SubtitleParser.parse(srt);
        assertEquals(3, segs.size());
        assertEquals(1000, segs.get(0).startMs());
        assertEquals(3500, segs.get(0).endMs());
        assertEquals("Hello world.", segs.get(0).text());
        assertEquals(60_250, segs.get(2).startMs());
    }

    @Test
    void 一条字幕的多行文本合并成一行() {
        // 保留换行会把一句话拆成两句分别翻译，指代关系当场断掉
        String srt = """
                1
                00:00:01,000 --> 00:00:04,000
                But what would it take
                to help us thrive?
                """;
        List<Segment> segs = SubtitleParser.parse(srt);
        assertEquals(1, segs.size());
        assertEquals("But what would it take to help us thrive?", segs.get(0).text());
    }

    @Test
    void vtt格式与内联标签() {
        String vtt = """
                WEBVTT

                NOTE 这是一段说明，不该被当成字幕

                00:01.000 --> 00:03.000 align:start position:0%
                <c.colorE5E5E5>Sawubona</c>

                00:04.000 --> 00:06.000
                I see you.
                """;
        List<Segment> segs = SubtitleParser.parse(vtt);
        assertEquals(2, segs.size());
        assertEquals(1000, segs.get(0).startMs());      // 省略小时段
        assertEquals("Sawubona", segs.get(0).text());   // 内联标签被剥掉
        assertEquals("I see you.", segs.get(1).text());
    }

    @Test
    void 毫秒不足三位按左对齐补零() {
        // .5 是 500ms 而不是 5ms——按右对齐补零会让整条轴错半秒
        List<Segment> segs = SubtitleParser.parse("00:00:01.5 --> 00:00:02.25\nhi\n");
        assertEquals(1, segs.size());
        assertEquals(1500, segs.get(0).startMs());
        assertEquals(2250, segs.get(0).endMs());
    }

    @Test
    void 没有空行分隔也能靠序号断开() {
        String srt = """
                1
                00:00:01,000 --> 00:00:02,000
                one
                2
                00:00:03,000 --> 00:00:04,000
                two
                """;
        List<Segment> segs = SubtitleParser.parse(srt);
        assertEquals(2, segs.size());
        assertEquals("one", segs.get(0).text());
        assertEquals("two", segs.get(1).text());
    }

    @Test
    void 非法与空白条目被丢弃() {
        String srt = """
                1
                00:00:05,000 --> 00:00:05,000
                零长度，丢

                2
                00:00:06,000 --> 00:00:04,000
                终点早于起点，丢

                3
                00:00:07,000 --> 00:00:08,000

                4
                00:00:09,000 --> 00:00:10,000
                只有这条留下
                """;
        List<Segment> segs = SubtitleParser.parse(srt);
        assertEquals(1, segs.size());
        assertEquals("只有这条留下", segs.get(0).text());
    }

    @Test
    void 乱序输入按起点排好序() {
        String srt = """
                00:00:09,000 --> 00:00:10,000
                后面的

                00:00:01,000 --> 00:00:02,000
                前面的
                """;
        List<Segment> segs = SubtitleParser.parse(srt);
        assertEquals("前面的", segs.get(0).text());
        assertEquals("后面的", segs.get(1).text());
    }

    @Test
    void 空输入与非字幕文本返回空列表() {
        assertTrue(SubtitleParser.parse(null).isEmpty());
        assertTrue(SubtitleParser.parse("").isEmpty());
        assertTrue(SubtitleParser.parse("这只是一段普通文本\n没有任何时间轴").isEmpty());
    }

    @Test
    void 扩展名判定() {
        assertTrue(SubtitleParser.isSubtitleFile("a.srt"));
        assertTrue(SubtitleParser.isSubtitleFile("A.VTT"));
        assertFalse(SubtitleParser.isSubtitleFile("a.ass"));
        assertFalse(SubtitleParser.isSubtitleFile("a.mp4"));
        assertFalse(SubtitleParser.isSubtitleFile(null));
    }
}
