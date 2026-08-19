package com.aifanyi.doc;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * OOXML 容器（docx/xlsx/pptx）公共骨架：ZIP 全量读入 → 只把含正文的 XML part
 * 解析成 DOM 原位改文本 → 重新序列化 → 其余条目字节级拷回重打包。
 */
abstract class OoxmlZipDoc extends ParsedDoc {

    /** ZIP 内全部条目（保持原顺序）。 */
    protected final Map<String, byte[]> entries = new LinkedHashMap<>();
    /** 被解析成 DOM、需要回写的 XML part。 */
    protected final Map<String, Document> parts = new LinkedHashMap<>();

    OoxmlZipDoc(byte[] bytes) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    entries.put(e.getName(), zin.readAllBytes());
                }
            }
        }
    }

    /** 译文按组回填：整段写入组内首个文本元素，其余清空（组=一个段落/单元格）。 */
    protected static void fillGroups(List<List<Element>> groups, List<String> translated) {
        for (int k = 0; k < groups.size(); k++) {
            List<Element> group = groups.get(k);
            Element first = group.get(0);
            first.setTextContent(translated.get(k));
            // 译文首尾可能带空格，必须声明保留，否则渲染时被吞掉
            first.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
            for (int i = 1; i < group.size(); i++) {
                group.get(i).setTextContent("");
            }
        }
    }

    /** 序列化已修改的 XML part 并重打包整个 ZIP。 */
    protected byte[] repack() throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        for (Map.Entry<String, Document> en : parts.entrySet()) {
            Transformer tr = tf.newTransformer();
            tr.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            en.getValue().setXmlStandalone(true);
            ByteArrayOutputStream xml = new ByteArrayOutputStream();
            tr.transform(new DOMSource(en.getValue()), new StreamResult(xml));
            entries.put(en.getKey(), xml.toByteArray());
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> en : entries.entrySet()) {
                zout.putNextEntry(new ZipEntry(en.getKey()));
                zout.write(en.getValue());
                zout.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}
