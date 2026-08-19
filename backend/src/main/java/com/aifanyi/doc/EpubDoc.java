package com.aifanyi.doc;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * ePub：本质是 ZIP（mimetype + OPF + 一堆 XHTML 章节）。
 * 逐章节用 jsoup XML 模式翻译文本节点，其余条目（图片/CSS/OPF/NCX）字节级原样拷回。
 * 重打包时 mimetype 必须是第一个条目且不压缩（ePub 规范硬性要求，阅读器据此识别）。
 */
final class EpubDoc extends ParsedDoc {

    private final Map<String, byte[]> entries = new LinkedHashMap<>();
    /** 章节名 → 解析后的 jsoup 文档与其文本节点。 */
    private final Map<String, Document> chapterDocs = new LinkedHashMap<>();
    private final List<TextNode> nodes = new ArrayList<>();
    private final List<String> segments = new ArrayList<>();

    EpubDoc(byte[] bytes) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    entries.put(e.getName(), zin.readAllBytes());
                }
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("不是有效的 ePub 文件（ZIP 为空）");
        }
        for (Map.Entry<String, byte[]> en : entries.entrySet()) {
            String name = en.getKey().toLowerCase(Locale.ROOT);
            if (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) {
                Document doc = Jsoup.parse(new String(en.getValue(), StandardCharsets.UTF_8),
                        "", Parser.xmlParser());
                doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
                        .escapeMode(Entities.EscapeMode.xhtml)
                        .charset(StandardCharsets.UTF_8).prettyPrint(false);
                List<TextNode> ts = JsoupText.collect(doc);
                if (!ts.isEmpty()) {
                    chapterDocs.put(en.getKey(), doc);
                    for (TextNode t : ts) {
                        nodes.add(t);
                        segments.add(JsoupText.textOf(t));
                    }
                }
            }
        }
    }

    @Override
    public List<String> segments() {
        return segments;
    }

    @Override
    public byte[] rebuild(List<String> translated) throws Exception {
        for (int k = 0; k < nodes.size(); k++) {
            JsoupText.fill(nodes.get(k), translated.get(k));
        }
        for (Map.Entry<String, Document> en : chapterDocs.entrySet()) {
            entries.put(en.getKey(), en.getValue().outerHtml().getBytes(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(bos)) {
            byte[] mime = entries.get("mimetype");
            if (mime != null) {
                ZipEntry me = new ZipEntry("mimetype");
                me.setMethod(ZipEntry.STORED);
                me.setSize(mime.length);
                CRC32 crc = new CRC32();
                crc.update(mime);
                me.setCrc(crc.getValue());
                zout.putNextEntry(me);
                zout.write(mime);
                zout.closeEntry();
            }
            for (Map.Entry<String, byte[]> en : entries.entrySet()) {
                if (en.getKey().equals("mimetype")) {
                    continue;
                }
                zout.putNextEntry(new ZipEntry(en.getKey()));
                zout.write(en.getValue());
                zout.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @Override
    public String outputExt() {
        return "epub";
    }
}
