package com.aifanyi.doc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 从零生成最小可用 DOCX（一段一 &lt;w:p&gt;），零依赖。
 * 给 PDF 翻译输出用：PDF 版式无法回填，译文落成 Word 文档最通用。
 */
final class MiniDocxWriter {

    private MiniDocxWriter() {
    }

    private static final String CONTENT_TYPES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
            """;

    private static final String RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
            """;

    static byte[] write(List<String> paragraphs) throws Exception {
        StringBuilder body = new StringBuilder();
        for (String p : paragraphs) {
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                    .append(escape(p))
                    .append("</w:t></w:r></w:p>");
        }
        String documentXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>%s</w:body></w:document>
                """.formatted(body);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(bos)) {
            put(zout, "[Content_Types].xml", CONTENT_TYPES);
            put(zout, "_rels/.rels", RELS);
            put(zout, "word/document.xml", documentXml);
        }
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream zout, String name, String content) throws Exception {
        zout.putNextEntry(new ZipEntry(name));
        zout.write(content.getBytes(StandardCharsets.UTF_8));
        zout.closeEntry();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
