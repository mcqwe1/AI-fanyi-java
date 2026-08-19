package com.aifanyi.doc;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DOCX：ZIP + OOXML。直接改 word/document.xml（及页眉/页脚/脚注/尾注）里的
 * &lt;w:t&gt; 文本，全部样式/图片/表格/编号原样保留，不依赖 POI。
 *
 * 粒度：以段落 &lt;w:p&gt; 为翻译单元（跨 run 的整句才有完整语境；逐 run 翻会把
 * 一句话切成碎片）。回填时译文整段写入该段第一个 &lt;w:t&gt;，其余 &lt;w:t&gt; 清空
 * —— 代价是段内局部加粗/变色会丢（整段样式以首个 run 为准），这是无版式引擎下的
 * 通行折衷（各大文档翻译工具同款行为）。
 */
final class DocxDoc extends OoxmlZipDoc {

    private static final String W_NS =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    /** 每个翻译段对应的该段落全部 w:t 元素（首个承接译文，其余清空）。 */
    private final List<List<Element>> paraTs = new ArrayList<>();
    private final List<String> segments = new ArrayList<>();

    DocxDoc(byte[] bytes) throws Exception {
        super(bytes);
        if (!entries.containsKey("word/document.xml")) {
            throw new IllegalArgumentException("不是有效的 docx 文件（缺少 word/document.xml）");
        }
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        for (String name : entries.keySet()) {
            if (name.equals("word/document.xml") || name.matches("word/(header|footer)\\d*\\.xml")
                    || name.equals("word/footnotes.xml") || name.equals("word/endnotes.xml")) {
                Document doc = dbf.newDocumentBuilder()
                        .parse(new ByteArrayInputStream(entries.get(name)));
                parts.put(name, doc);
                collectParagraphs(doc);
            }
        }
    }

    /**
     * 按「最近祖先 w:p」把 w:t 分组成段落（文本框会出现 w:p 嵌套，
     * 直接对外层段落取全部后代 w:t 会重复计入内层文本）。
     */
    private void collectParagraphs(Document doc) {
        NodeList ts = doc.getElementsByTagNameNS(W_NS, "t");
        Map<Node, List<Element>> byPara = new LinkedHashMap<>();
        for (int i = 0; i < ts.getLength(); i++) {
            Element t = (Element) ts.item(i);
            Node p = t.getParentNode();
            while (p != null && !(p instanceof Element el
                    && W_NS.equals(el.getNamespaceURI()) && "p".equals(el.getLocalName()))) {
                p = p.getParentNode();
            }
            if (p != null) {
                byPara.computeIfAbsent(p, k -> new ArrayList<>()).add(t);
            }
        }
        for (List<Element> group : byPara.values()) {
            StringBuilder sb = new StringBuilder();
            for (Element t : group) {
                sb.append(t.getTextContent());
            }
            String text = sb.toString();
            if (!text.isBlank()) {
                paraTs.add(group);
                segments.add(text);
            }
        }
    }

    @Override
    public List<String> segments() {
        return segments;
    }

    @Override
    public byte[] rebuild(List<String> translated) throws Exception {
        fillGroups(paraTs, translated);
        return repack();
    }

    @Override
    public String outputExt() {
        return "docx";
    }
}
