package com.aifanyi.doc;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** jsoup 文档的可翻译文本节点收集/回填（HTML 与 ePub 共用）。 */
final class JsoupText {

    /** 这些元素内部的文本不翻：代码/样式/脚本/预格式化。 */
    private static final Set<String> SKIP = Set.of("script", "style", "code", "pre",
            "noscript", "template", "svg", "math", "kbd", "samp", "var");

    private JsoupText() {
    }

    /** 收集文档中所有可翻译文本节点（含 title），文档序。 */
    static List<TextNode> collect(Document doc) {
        List<TextNode> nodes = new ArrayList<>();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode t && !t.isBlank() && !insideSkipped(t)) {
                    nodes.add(t);
                }
            }
        }, doc);
        return nodes;
    }

    private static boolean insideSkipped(TextNode t) {
        for (Element p = (Element) (t.parent() instanceof Element e ? e : null);
             p != null; p = p.parent()) {
            String name = p.normalName();
            if (SKIP.contains(name)) {
                return true;
            }
            // head 里除 title 外（meta/link 等）没有可见文本
            if (name.equals("head")) {
                return !hasAncestorOrSelf(t, "title");
            }
        }
        return false;
    }

    private static boolean hasAncestorOrSelf(TextNode t, String name) {
        for (Element p = t.parent() instanceof Element e ? e : null; p != null; p = p.parent()) {
            if (p.normalName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提取节点文本为待翻译段：保留首尾空白（回填时还原），中间空白折叠交给 LLM。
     * 返回 strip 后的文本；空白节点不应传入（collect 已过滤）。
     */
    static String textOf(TextNode t) {
        return t.getWholeText().strip();
    }

    /** 回填译文：保留原节点的首尾空白（避免行内元素间的空格被吃掉）。 */
    static void fill(TextNode t, String translated) {
        String whole = t.getWholeText();
        String lead = whole.substring(0, whole.length() - whole.stripLeading().length());
        String trail = whole.substring(whole.stripTrailing().length());
        t.text(lead + translated + trail);
    }
}
