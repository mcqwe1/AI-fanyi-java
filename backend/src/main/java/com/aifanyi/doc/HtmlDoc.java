package com.aifanyi.doc;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.TextNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * HTML：jsoup 解析，只翻可见文本节点（跳过 script/style/code/pre 等），
 * 标签、属性、结构原样保留；输出统一 UTF-8（并同步更新 meta charset）。
 */
final class HtmlDoc extends ParsedDoc {

    private final Document doc;
    private final List<TextNode> nodes;
    private final List<String> segments = new ArrayList<>();

    HtmlDoc(String content) {
        this.doc = Jsoup.parse(content);
        this.doc.outputSettings().charset(StandardCharsets.UTF_8).prettyPrint(false);
        this.nodes = JsoupText.collect(doc);
        for (TextNode t : nodes) {
            segments.add(JsoupText.textOf(t));
        }
    }

    @Override
    public List<String> segments() {
        return segments;
    }

    @Override
    public byte[] rebuild(List<String> translated) {
        for (int k = 0; k < nodes.size(); k++) {
            JsoupText.fill(nodes.get(k), translated.get(k));
        }
        // meta charset 改成 UTF-8，与输出字节一致（原文件可能声明 GBK 等）
        doc.charset(StandardCharsets.UTF_8);
        return doc.outerHtml().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String outputExt() {
        return "html";
    }
}
