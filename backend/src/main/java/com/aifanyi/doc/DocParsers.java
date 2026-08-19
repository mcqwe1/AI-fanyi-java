package com.aifanyi.doc;

import com.aifanyi.media.CharsetSniffer;

import java.util.Locale;
import java.util.Set;

/** 文档解析入口：按扩展名分发到对应格式实现。 */
public final class DocParsers {

    /** 支持的输入扩展名。 */
    public static final Set<String> SUPPORTED = Set.of(
            "pdf", "epub", "html", "htm", "txt", "json", "docx", "xlsx", "pptx",
            "md", "markdown", "srt", "vtt", "ass");

    private DocParsers() {
    }

    public static boolean supported(String ext) {
        return ext != null && SUPPORTED.contains(ext.toLowerCase(Locale.ROOT));
    }

    /**
     * 解析文档。二进制容器（pdf/epub/docx/xlsx/pptx）直接吃字节；
     * 文本类格式先过编码嗅探（UTF-8/GBK/BOM 自动识别）。
     */
    public static ParsedDoc parse(String ext, byte[] bytes) throws Exception {
        String e = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        return switch (e) {
            case "pdf" -> new PdfDoc(bytes);
            case "epub" -> new EpubDoc(bytes);
            case "docx" -> new DocxDoc(bytes);
            case "xlsx" -> new XlsxDoc(bytes);
            case "pptx" -> new PptxDoc(bytes);
            case "html", "htm" -> new HtmlDoc(CharsetSniffer.decode(bytes));
            case "json" -> new JsonDoc(CharsetSniffer.decode(bytes));
            case "md", "markdown" -> new MarkdownDoc(CharsetSniffer.decode(bytes));
            case "txt" -> new TxtDoc(CharsetSniffer.decode(bytes));
            case "srt", "vtt", "ass" -> new SubtitleDoc(CharsetSniffer.decode(bytes), e);
            default -> throw new IllegalArgumentException(
                    "不支持的文档格式: " + ext + "，支持 " + String.join(" / ", SUPPORTED));
        };
    }
}
