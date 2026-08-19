package com.aifanyi.doc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown：按行翻译正文，保留全部结构标记。
 *  - 围栏代码块（``` / ~~~）、水平线、表格分隔行、链接引用定义、纯 HTML 行不翻；
 *  - 标题/列表/引用等行只翻前缀标记之后的文本；
 *  - 表格行按单元格逐格翻译，竖线结构不动。
 * 行内代码/链接语法交给 LLM 保留（提示词约束 + 实测模型对 `code`/[x](url) 保持良好）。
 */
final class MarkdownDoc extends ParsedDoc {

    /** 行首结构前缀：缩进 + 若干层引用 > + 一个标题/列表/有序号标记。 */
    private static final Pattern PREFIX = Pattern.compile(
            "^(\\s*(?:>\\s*)*(?:#{1,6}\\s+|[-*+]\\s+|\\d{1,3}[.)]\\s+)?)");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");
    private static final Pattern HR = Pattern.compile("^\\s*([-*_]\\s*){3,}$");
    /** 表格分隔行：只由 | : - 空格构成。 */
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?[\\s:|-]+\\|?\\s*$");
    private static final Pattern LINK_DEF = Pattern.compile("^\\s*\\[[^\\]]+\\]:\\s+\\S+");

    /** 一个翻译槽位：行号 + 该行的重建方式。 */
    private record Slot(int line, String prefix, List<String> cells, int firstSegIdx) {
    }

    private final String[] lines;
    private final List<Slot> slots = new ArrayList<>();
    private final List<String> segments = new ArrayList<>();

    MarkdownDoc(String content) {
        this.lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        boolean inFence = false;
        String fenceMark = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher fm = FENCE.matcher(line);
            if (fm.find()) {
                String mark = fm.group(1);
                if (!inFence) {
                    inFence = true;
                    fenceMark = mark;
                } else if (mark.equals(fenceMark)) {
                    inFence = false;
                }
                continue;
            }
            if (inFence || line.isBlank() || HR.matcher(line).matches()
                    || TABLE_SEP.matcher(line).matches() || LINK_DEF.matcher(line).find()
                    || line.stripLeading().startsWith("<")) {
                continue;
            }
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("|")) {
                // 表格行：逐格翻译。split 保留空尾格，首尾空串对应行首/行尾竖线外侧
                List<String> cells = List.of(line.split("\\|", -1));
                int first = segments.size();
                boolean any = false;
                for (String c : cells) {
                    if (!c.isBlank()) {
                        segments.add(c.strip());
                        any = true;
                    }
                }
                if (any) {
                    slots.add(new Slot(i, null, cells, first));
                }
                continue;
            }
            Matcher pm = PREFIX.matcher(line);
            String prefix = pm.find() ? pm.group(1) : "";
            String body = line.substring(prefix.length());
            if (body.isBlank()) {
                continue;
            }
            slots.add(new Slot(i, prefix, null, segments.size()));
            segments.add(body);
        }
    }

    @Override
    public List<String> segments() {
        return segments;
    }

    @Override
    public byte[] rebuild(List<String> translated) {
        String[] out = lines.clone();
        for (Slot s : slots) {
            if (s.cells() == null) {
                out[s.line()] = s.prefix() + translated.get(s.firstSegIdx());
            } else {
                StringBuilder sb = new StringBuilder();
                int k = s.firstSegIdx();
                for (int c = 0; c < s.cells().size(); c++) {
                    if (c > 0) {
                        sb.append('|');
                    }
                    String cell = s.cells().get(c);
                    sb.append(cell.isBlank() ? cell : " " + translated.get(k++).strip() + " ");
                }
                out[s.line()] = sb.toString();
            }
        }
        return String.join("\n", out).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String outputExt() {
        return "md";
    }
}
