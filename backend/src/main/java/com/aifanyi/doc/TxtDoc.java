package com.aifanyi.doc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 纯文本：逐行翻译，空行/空白行原样保留（维持段落结构）。 */
final class TxtDoc extends ParsedDoc {

    private final String[] lines;
    private final List<Integer> slotLine = new ArrayList<>();
    private final List<String> segments = new ArrayList<>();

    TxtDoc(String content) {
        this.lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                slotLine.add(i);
                segments.add(lines[i]);
            }
        }
    }

    @Override
    public List<String> segments() {
        return segments;
    }

    @Override
    public byte[] rebuild(List<String> translated) {
        String[] out = lines.clone();
        for (int k = 0; k < slotLine.size(); k++) {
            out[slotLine.get(k)] = translated.get(k);
        }
        return String.join("\n", out).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String outputExt() {
        return "txt";
    }
}
