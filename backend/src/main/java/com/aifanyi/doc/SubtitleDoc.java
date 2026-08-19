package com.aifanyi.doc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 字幕：SRT / VTT / ASS。只翻对白文本，序号/时间轴/样式/元数据原样保留。
 *  - SRT/VTT：序号行、时间轴行、WEBVTT 头、NOTE/STYLE/REGION 块不动，其余行翻译；
 *  - ASS：仅 Dialogue 行的文本字段（第 10 个逗号分隔字段）翻译；行首特效标签块
 *    {\...} 保留在译文前，行内其余标签剥除（无版式引擎下保效果标签会让 LLM 输出
 *    失控，剥除是字幕翻译工具的通行做法）；\N 软换行并为空格翻译。
 */
final class SubtitleDoc extends ParsedDoc {

    /** ASS 行内特效标签块。 */
    private static final Pattern ASS_TAG = Pattern.compile("\\{[^}]*\\}");

    private final String ext;
    private final String[] lines;
    /** 槽位：行号；ASS 行还带 Dialogue 前 9 个字段的前缀和特效标签前缀。 */
    private record Slot(int line, String assPrefix, String tagPrefix) {
    }

    private final List<Slot> slots = new ArrayList<>();
    private final List<String> segments = new ArrayList<>();

    SubtitleDoc(String content, String ext) {
        this.ext = ext;
        this.lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        if (ext.equals("ass")) {
            parseAss();
        } else {
            parseSrtVtt();
        }
    }

    private void parseSrtVtt() {
        boolean inMetaBlock = false;                        // VTT 的 NOTE/STYLE/REGION 块
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            if (t.isEmpty()) {
                inMetaBlock = false;
                continue;
            }
            if (inMetaBlock) {
                continue;
            }
            String up = t.toUpperCase(java.util.Locale.ROOT);
            if (up.startsWith("WEBVTT") || up.startsWith("NOTE") || up.startsWith("STYLE")
                    || up.startsWith("REGION")) {
                inMetaBlock = !up.startsWith("WEBVTT");
                continue;
            }
            if (t.matches("\\d+") || t.contains("-->")) {
                continue;                                   // 序号行 / 时间轴行
            }
            slots.add(new Slot(i, null, null));
            segments.add(t);
        }
    }

    private void parseAss() {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.startsWith("Dialogue:")) {
                continue;
            }
            // Dialogue: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
            String[] f = line.split(",", 10);
            if (f.length < 10 || f[9].isBlank()) {
                continue;
            }
            String text = f[9];
            // 行首连续的特效标签块整体保留为前缀
            Matcher m = Pattern.compile("^(\\{[^}]*\\})+").matcher(text);
            String tagPrefix = m.find() ? m.group() : "";
            String body = ASS_TAG.matcher(text).replaceAll("")
                    .replace("\\N", " ").replace("\\n", " ").strip();
            if (body.isEmpty()) {
                continue;
            }
            String assPrefix = line.substring(0, line.length() - text.length());
            slots.add(new Slot(i, assPrefix, tagPrefix));
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
        for (int k = 0; k < slots.size(); k++) {
            Slot s = slots.get(k);
            if (s.assPrefix() == null) {
                out[s.line()] = translated.get(k);
            } else {
                out[s.line()] = s.assPrefix() + s.tagPrefix() + translated.get(k);
            }
        }
        return String.join("\n", out).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String outputExt() {
        return ext;
    }
}
