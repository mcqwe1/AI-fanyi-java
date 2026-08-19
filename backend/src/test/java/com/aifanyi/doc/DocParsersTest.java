package com.aifanyi.doc;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文档解析引擎的解析→伪翻译→重建往返测试（不依赖 LLM）。
 * 伪翻译：每段前加「§」标记，验证：段落抽取正确、结构行原样保留、译文落在正确位置。
 */
class DocParsersTest {

    /** 伪翻译：全部段落加 § 前缀。 */
    private static List<String> mark(List<String> segments) {
        List<String> out = new ArrayList<>();
        for (String s : segments) {
            out.add("§" + s);
        }
        return out;
    }

    // ---------------- TXT ----------------

    @Test
    void txtKeepsBlankLines() throws Exception {
        ParsedDoc doc = DocParsers.parse("txt", "第一段\n\n第二段\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("第一段", "第二段"), doc.segments());
        String out = new String(doc.rebuild(mark(doc.segments())), StandardCharsets.UTF_8);
        assertEquals("§第一段\n\n§第二段\n", out);
    }

    // ---------------- Markdown ----------------

    @Test
    void markdownPreservesStructure() throws Exception {
        String md = """
                # Title

                Some paragraph.

                ```java
                int a = 1; // code untouched
                ```

                - item one
                > quoted text

                | Name | Desc |
                |------|------|
                | foo  | bar  |
                """;
        ParsedDoc doc = DocParsers.parse("md", md.getBytes(StandardCharsets.UTF_8));
        assertTrue(doc.segments().contains("Title"));
        assertTrue(doc.segments().contains("item one"));
        assertTrue(doc.segments().contains("foo"));
        assertFalse(doc.segments().stream().anyMatch(s -> s.contains("int a = 1")),
                "代码块不应送翻");
        String out = new String(doc.rebuild(mark(doc.segments())), StandardCharsets.UTF_8);
        assertTrue(out.contains("# §Title"), "标题标记应保留");
        assertTrue(out.contains("int a = 1; // code untouched"), "代码块应原样");
        assertTrue(out.contains("- §item one"));
        assertTrue(out.contains("|------|------|"), "表格分隔行应原样");
        assertTrue(out.contains("| §foo | §bar |"));
    }

    // ---------------- JSON ----------------

    @Test
    void jsonTranslatesOnlyValues() throws Exception {
        String json = """
                {"title": "Hello world", "lang": "en_US", "url": "https://x.com/a",
                 "count": 3, "items": ["Click me", 42]}
                """;
        ParsedDoc doc = DocParsers.parse("json", json.getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("Hello world", "Click me"), doc.segments());
        String out = new String(doc.rebuild(mark(doc.segments())), StandardCharsets.UTF_8);
        assertTrue(out.contains("\"§Hello world\""));
        assertTrue(out.contains("\"en_US\""), "标识符值不应翻译");
        assertTrue(out.contains("\"https://x.com/a\""), "URL 不应翻译");
        assertTrue(out.contains("\"title\""), "键名不应翻译");
        assertTrue(out.contains("\"§Click me\""));
    }

    @Test
    void jsonSingleWordIsTranslatable() {
        assertTrue(JsonDoc.translatable("Hello"));
        assertTrue(JsonDoc.translatable("保存成功"));
        assertFalse(JsonDoc.translatable("en_US"));
        assertFalse(JsonDoc.translatable("user.name"));
        assertFalse(JsonDoc.translatable("2024-01-01"));
        assertFalse(JsonDoc.translatable("https://example.com/path"));
        assertFalse(JsonDoc.translatable("a@b.com"));
    }

    // ---------------- HTML ----------------

    @Test
    void htmlPreservesTagsSkipsCode() throws Exception {
        String html = """
                <!doctype html><html><head><title>Page</title></head><body>
                <h1>Heading</h1><p>Text with <b>bold</b> part.</p>
                <pre>keep this</pre><script>var x=1;</script>
                </body></html>
                """;
        ParsedDoc doc = DocParsers.parse("html", html.getBytes(StandardCharsets.UTF_8));
        assertTrue(doc.segments().contains("Heading"));
        assertTrue(doc.segments().contains("Page"), "title 应送翻");
        assertFalse(doc.segments().contains("keep this"), "pre 不应送翻");
        assertFalse(doc.segments().stream().anyMatch(s -> s.contains("var x")), "script 不应送翻");
        String out = new String(doc.rebuild(mark(doc.segments())), StandardCharsets.UTF_8);
        assertTrue(out.contains("<h1>§Heading</h1>"));
        assertTrue(out.contains("<b>§bold</b>"), "行内标签应保留");
        assertTrue(out.contains("keep this"));
    }

    // ---------------- 字幕 ----------------

    @Test
    void srtKeepsTimeline() throws Exception {
        String srt = """
                1
                00:00:01,000 --> 00:00:03,000
                Hello there

                2
                00:00:04,000 --> 00:00:06,000
                Second line
                """;
        ParsedDoc doc = DocParsers.parse("srt", srt.getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("Hello there", "Second line"), doc.segments());
        String out = new String(doc.rebuild(mark(doc.segments())), StandardCharsets.UTF_8);
        assertTrue(out.contains("00:00:01,000 --> 00:00:03,000"));
        assertTrue(out.contains("§Hello there"));
    }

    @Test
    void vttKeepsHeaderAndNotes() throws Exception {
        String vtt = """
                WEBVTT

                NOTE
                this comment stays

                00:01.000 --> 00:04.000
                Where are you?
                """;
        ParsedDoc doc = DocParsers.parse("vtt", vtt.getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("Where are you?"), doc.segments());
        String out = new String(doc.rebuild(mark(doc.segments())), StandardCharsets.UTF_8);
        assertTrue(out.contains("WEBVTT"));
        assertTrue(out.contains("this comment stays"));
        assertTrue(out.contains("§Where are you?"));
    }

    @Test
    void assKeepsFieldsAndLeadingTags() throws Exception {
        String ass = """
                [Script Info]
                Title: t

                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\pos(1,2)}Hello,world
                """;
        ParsedDoc doc = DocParsers.parse("ass", ass.getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("Hello,world"), doc.segments(), "文本字段含逗号也要完整");
        String out = new String(doc.rebuild(mark(doc.segments())), StandardCharsets.UTF_8);
        assertTrue(out.contains("Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\pos(1,2)}§Hello,world"));
        assertTrue(out.contains("Format: Layer"));
    }

    // ---------------- DOCX ----------------

    @Test
    void docxRoundTrip() throws Exception {
        byte[] docx = MiniDocxWriter.write(List.of("First paragraph.", "第二段中文。"));
        ParsedDoc doc = DocParsers.parse("docx", docx);
        assertEquals(List.of("First paragraph.", "第二段中文。"), doc.segments());
        byte[] rebuilt = doc.rebuild(mark(doc.segments()));
        // 重建物应仍是可解析的 docx，且译文在位
        ParsedDoc again = DocParsers.parse("docx", rebuilt);
        assertEquals(List.of("§First paragraph.", "§第二段中文。"), again.segments());
    }

    @Test
    void docxMultiRunParagraphMergesAsOneSegment() throws Exception {
        // 手工构造多 run 段落：一句话被拆成 3 个 w:t
        String documentXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body><w:p>
                <w:r><w:t>He</w:t></w:r><w:r><w:t>llo </w:t></w:r><w:r><w:t>world</w:t></w:r>
                </w:p></w:body></w:document>
                """;
        byte[] docx = zip(Map.of(
                "[Content_Types].xml", "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
                "word/document.xml", documentXml));
        ParsedDoc doc = DocParsers.parse("docx", docx);
        assertEquals(List.of("Hello world"), doc.segments(), "跨 run 应合并成整段");
        byte[] rebuilt = doc.rebuild(List.of("你好世界"));
        String xml = new String(unzip(rebuilt).get("word/document.xml"), StandardCharsets.UTF_8);
        assertTrue(xml.contains("你好世界"));
        assertFalse(xml.contains("llo"), "其余 run 应清空");
    }

    // ---------------- ePub ----------------

    @Test
    void epubRoundTripKeepsMimetypeFirstAndStored() throws Exception {
        String chapter = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Ch1</title></head>
                <body><p>Once upon a time.</p></body></html>
                """;
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("mimetype", "application/epub+zip");
        entries.put("META-INF/container.xml", "<?xml version=\"1.0\"?><container/>");
        entries.put("OEBPS/ch1.xhtml", chapter);
        byte[] epub = zip(entries);
        ParsedDoc doc = DocParsers.parse("epub", epub);
        assertTrue(doc.segments().contains("Once upon a time."));
        byte[] rebuilt = doc.rebuild(mark(doc.segments()));

        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(rebuilt))) {
            ZipEntry first = zin.getNextEntry();
            assertEquals("mimetype", first.getName(), "mimetype 必须是第一个条目");
            assertEquals(ZipEntry.STORED, first.getMethod(), "mimetype 必须不压缩");
        }
        String ch = new String(unzip(rebuilt).get("OEBPS/ch1.xhtml"), StandardCharsets.UTF_8);
        assertTrue(ch.contains("§Once upon a time."));
        assertTrue(new String(unzip(rebuilt).get("META-INF/container.xml"),
                StandardCharsets.UTF_8).contains("<container/>"), "非章节条目应原样");
    }

    // ---------------- PDF 解析 + 原位回填 ----------------

    @Test
    void pdfParseAndRebuildInPlace() throws Exception {
        // 用 pdfbox 造一个两行文本的真实 PDF
        byte[] pdf;
        try (org.apache.pdfbox.pdmodel.PDDocument pd = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page =
                    new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            pd.addPage(page);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(pd, page)) {
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Hello world from the first paragraph.");
                cs.newLineAtOffset(0, -60);
                cs.showText("Second block sits far below.");
                cs.endText();
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            pd.save(bos);
            pdf = bos.toByteArray();
        }

        ParsedDoc doc = DocParsers.parse("pdf", pdf);
        assertEquals(2, doc.segments().size(), "行距 60pt 远超行高，应聚成两个视觉段");
        assertTrue(doc.segments().get(0).contains("Hello world"));

        byte[] rebuilt = doc.rebuild(List.of("你好，第一段。", "第二块。"));
        String head = new String(rebuilt, 0, 4, StandardCharsets.US_ASCII);
        if ("%PDF".equals(head)) {
            assertEquals("pdf", doc.outputExt(), "PDF 原位回填成功时输出仍为 PDF");
            // 回填后的 PDF 里应能抽到译文
            try (org.apache.pdfbox.pdmodel.PDDocument pd =
                         org.apache.pdfbox.Loader.loadPDF(rebuilt)) {
                String text = new org.apache.pdfbox.text.PDFTextStripper().getText(pd);
                assertTrue(text.contains("你好"), "译文应写进了 PDF：" + text);
            }
        } else {
            // 无中文字体的环境降级 DOCX（zip 以 PK 开头）
            assertEquals("docx", doc.outputExt());
            assertEquals("PK", new String(rebuilt, 0, 2, StandardCharsets.US_ASCII));
        }
    }

    // ---------------- XLSX ----------------

    @Test
    void xlsxTranslatesSharedAndInlineStringsSkipsNumbers() throws Exception {
        // 共享字符串：普通、富文本（多 run）、纯数字；工作表内联字符串各一个
        String shared = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="3" uniqueCount="3">
                <si><t>Hello world</t></si>
                <si><r><t>Bo</t></r><r><t>ld part</t></r></si>
                <si><t>123.45</t></si>
                </sst>
                """;
        String sheet = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData><row r="1">
                <c r="A1" t="s"><v>0</v></c>
                <c r="B1" t="inlineStr"><is><t>Inline text</t></is></c>
                <c r="C1"><v>42</v></c>
                </row></sheetData></worksheet>
                """;
        byte[] xlsx = zip(Map.of(
                "xl/workbook.xml", "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"/>",
                "xl/sharedStrings.xml", shared,
                "xl/worksheets/sheet1.xml", sheet));
        ParsedDoc doc = DocParsers.parse("xlsx", xlsx);
        assertEquals(List.of("Hello world", "Bold part", "Inline text"), doc.segments(),
                "富文本应合并成整段，纯数字不送翻");
        byte[] rebuilt = doc.rebuild(mark(doc.segments()));
        Map<String, byte[]> out = unzip(rebuilt);
        String sst = new String(out.get("xl/sharedStrings.xml"), StandardCharsets.UTF_8);
        assertTrue(sst.contains("§Hello world"));
        assertTrue(sst.contains("§Bold part"));
        assertTrue(sst.contains("123.45"), "数字应原样");
        String sh = new String(out.get("xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8);
        assertTrue(sh.contains("§Inline text"));
        assertTrue(sh.contains("<v>42</v>"), "数值单元格应原样");
    }

    // ---------------- PPTX ----------------

    @Test
    void pptxTranslatesSlidesInOrderSkipsMasters() throws Exception {
        String slideTpl = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                       xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                <p:cSld><p:spTree><p:sp><p:txBody>
                <a:p><a:r><a:t>%s</a:t></a:r><a:r><a:t>%s</a:t></a:r></a:p>
                </p:txBody></p:sp></p:spTree></p:cSld></p:sld>
                """;
        String master = slideTpl.formatted("Click to edit ", "master style");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("ppt/presentation.xml", "<?xml version=\"1.0\"?><p:presentation xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"/>");
        // 故意乱序放入，验证按编号排序
        entries.put("ppt/slides/slide10.xml", slideTpl.formatted("Tenth ", "slide"));
        entries.put("ppt/slides/slide2.xml", slideTpl.formatted("Second ", "slide"));
        entries.put("ppt/slideMasters/slideMaster1.xml", master);
        entries.put("ppt/notesSlides/notesSlide2.xml", slideTpl.formatted("Speaker ", "notes"));
        byte[] pptx = zip(entries);
        ParsedDoc doc = DocParsers.parse("pptx", pptx);
        assertEquals(List.of("Second slide", "Tenth slide", "Speaker notes"), doc.segments(),
                "跨 run 合并、按幻灯片编号排序、备注在正文后、母版不送翻");
        byte[] rebuilt = doc.rebuild(mark(doc.segments()));
        Map<String, byte[]> out = unzip(rebuilt);
        assertTrue(new String(out.get("ppt/slides/slide2.xml"), StandardCharsets.UTF_8)
                .contains("§Second slide"));
        assertTrue(new String(out.get("ppt/notesSlides/notesSlide2.xml"), StandardCharsets.UTF_8)
                .contains("§Speaker notes"));
        assertTrue(new String(out.get("ppt/slideMasters/slideMaster1.xml"), StandardCharsets.UTF_8)
                .contains("Click to edit "), "母版应原样");
    }

    // ---------------- zip 工具 ----------------

    private static byte[] zip(Map<String, ?> entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(bos)) {
            for (Map.Entry<String, ?> e : entries.entrySet()) {
                zout.putNextEntry(new ZipEntry(e.getKey()));
                Object v = e.getValue();
                zout.write(v instanceof byte[] b ? b : ((String) v).getBytes(StandardCharsets.UTF_8));
                zout.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    out.put(e.getName(), zin.readAllBytes());
                }
            }
        }
        return out;
    }
}
