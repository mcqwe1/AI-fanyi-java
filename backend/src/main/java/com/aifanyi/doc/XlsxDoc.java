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
 * XLSX：ZIP + OOXML。文本集中在 xl/sharedStrings.xml 的 &lt;si&gt;（共享字符串）
 * 与各工作表 &lt;c t="inlineStr"&gt;&lt;is&gt; 内联字符串，两处的 &lt;t&gt; 原位回填即可，
 * 公式/数字/样式/图表全部不动 —— 格式完整保留，不依赖 POI。
 *
 * 粒度：以一个 &lt;si&gt;/&lt;is&gt;（即一个单元格文本）为翻译单元；富文本单元格
 * （多个 &lt;r&gt; run）拼成整句翻译，译文写入首个 &lt;t&gt;，其余清空（同 DocxDoc 折衷）。
 * 纯数字/空白单元格不送翻。
 */
final class XlsxDoc extends OoxmlZipDoc {

    private static final String MAIN_NS =
            "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    /** 每个翻译段对应的 t 元素组（首个承接译文，其余清空）。 */
    private final List<List<Element>> groups = new ArrayList<>();
    private final List<String> segments = new ArrayList<>();

    XlsxDoc(byte[] bytes) throws Exception {
        super(bytes);
        if (!entries.containsKey("xl/workbook.xml")) {
            throw new IllegalArgumentException("不是有效的 xlsx 文件（缺少 xl/workbook.xml）");
        }
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        // 共享字符串表：绝大多数单元格文本都在这里
        if (entries.containsKey("xl/sharedStrings.xml")) {
            Document doc = dbf.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(entries.get("xl/sharedStrings.xml")));
            parts.put("xl/sharedStrings.xml", doc);
            collect(doc, "si");
        }
        // 内联字符串：少数写入方（如流式导出）直接把文本放在工作表里
        for (String name : entries.keySet()) {
            if (name.matches("xl/worksheets/sheet\\d+\\.xml")) {
                Document doc = dbf.newDocumentBuilder()
                        .parse(new ByteArrayInputStream(entries.get(name)));
                int before = segments.size();
                collect(doc, "is");
                if (segments.size() > before) {
                    parts.put(name, doc);
                }
            }
        }
    }

    /** 把每个容器元素（si/is）内的全部 w:t 归为一组，拼成一个翻译段。 */
    private void collect(Document doc, String container) {
        NodeList list = doc.getElementsByTagNameNS(MAIN_NS, container);
        for (int i = 0; i < list.getLength(); i++) {
            Element c = (Element) list.item(i);
            List<Element> ts = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            NodeList tn = c.getElementsByTagNameNS(MAIN_NS, "t");
            for (int k = 0; k < tn.getLength(); k++) {
                Element t = (Element) tn.item(k);
                // rPh（拼音注音）里的 t 不是正文，跳过
                if (!insidePhonetic(t, c)) {
                    ts.add(t);
                    sb.append(t.getTextContent());
                }
            }
            String text = sb.toString();
            if (!ts.isEmpty() && !text.isBlank() && !isNumericLike(text)) {
                groups.add(ts);
                segments.add(text);
            }
        }
    }

    private static boolean insidePhonetic(Element t, Element container) {
        for (Node p = t.getParentNode(); p != null && p != container; p = p.getParentNode()) {
            if (p instanceof Element el && "rPh".equals(el.getLocalName())) {
                return true;
            }
        }
        return false;
    }

    /** 纯数字/日期样式字符串不送翻（翻译反而可能被改写）。 */
    private static boolean isNumericLike(String s) {
        return s.strip().matches("[-+]?[\\d.,:/%\\s]+");
    }

    @Override
    public List<String> segments() {
        return segments;
    }

    @Override
    public byte[] rebuild(List<String> translated) throws Exception {
        fillGroups(groups, translated);
        return repack();
    }

    @Override
    public String outputExt() {
        return "xlsx";
    }
}
