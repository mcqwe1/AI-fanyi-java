package com.aifanyi.doc;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PPTX：ZIP + OOXML。改 ppt/slides/slideN.xml 与演讲者备注
 * ppt/notesSlides/notesSlideN.xml 里的 &lt;a:t&gt; 文本，母版/版式不动
 * （那里是占位符模板，不是内容），版式/图片/动画完整保留，不依赖 POI。
 *
 * 粒度：以段落 &lt;a:p&gt; 为翻译单元（同 DocxDoc：跨 run 整句才有语境），
 * 译文写入段内首个 &lt;a:t&gt;，其余清空。幻灯片按编号排序保证阅读序。
 */
final class PptxDoc extends OoxmlZipDoc {

    private static final String A_NS =
            "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final Pattern SLIDE_NO = Pattern.compile("(\\d+)\\.xml$");

    private final List<List<Element>> paraTs = new ArrayList<>();
    private final List<String> segments = new ArrayList<>();

    PptxDoc(byte[] bytes) throws Exception {
        super(bytes);
        if (!entries.containsKey("ppt/presentation.xml")) {
            throw new IllegalArgumentException("不是有效的 pptx 文件（缺少 ppt/presentation.xml）");
        }
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        List<String> targets = entries.keySet().stream()
                .filter(n -> n.matches("ppt/slides/slide\\d+\\.xml")
                        || n.matches("ppt/notesSlides/notesSlide\\d+\\.xml"))
                .sorted(Comparator
                        .comparing((String n) -> n.startsWith("ppt/notesSlides/") ? 1 : 0)
                        .thenComparingInt(PptxDoc::slideNo))
                .toList();
        for (String name : targets) {
            Document doc = dbf.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(entries.get(name)));
            int before = segments.size();
            collectParagraphs(doc);
            if (segments.size() > before) {
                parts.put(name, doc);
            }
        }
    }

    private static int slideNo(String name) {
        Matcher m = SLIDE_NO.matcher(name);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** 按「最近祖先 a:p」把 a:t 分组成段落（同 DocxDoc 的分组口径）。 */
    private void collectParagraphs(Document doc) {
        NodeList ts = doc.getElementsByTagNameNS(A_NS, "t");
        java.util.Map<Node, List<Element>> byPara = new java.util.LinkedHashMap<>();
        for (int i = 0; i < ts.getLength(); i++) {
            Element t = (Element) ts.item(i);
            Node p = t.getParentNode();
            while (p != null && !(p instanceof Element el
                    && A_NS.equals(el.getNamespaceURI()) && "p".equals(el.getLocalName()))) {
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
        return "pptx";
    }
}
