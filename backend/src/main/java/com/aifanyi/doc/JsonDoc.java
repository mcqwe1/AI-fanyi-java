package com.aifanyi.doc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JSON：只翻译字符串「值」，键名/数字/布尔/结构一律不动（典型场景：i18n 语言包、配置文案）。
 * 跳过明显不是自然语言的值：URL、纯符号/数字、无字母字符的短串。
 * 输出统一 2 空格缩进的 pretty JSON（键序保持原文件顺序）。
 */
final class JsonDoc extends ParsedDoc {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode root;
    /** 待回填槽位：父节点 + 键名（对象）或下标（数组）。 */
    private final List<Object[]> slots = new ArrayList<>();
    private final List<String> segments = new ArrayList<>();

    JsonDoc(String content) throws Exception {
        this.root = mapper.readTree(content);
        collect(root);
    }

    private void collect(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            for (Map.Entry<String, JsonNode> e : obj.properties()) {
                JsonNode v = e.getValue();
                if (v instanceof TextNode t && translatable(t.textValue())) {
                    slots.add(new Object[]{obj, e.getKey()});
                    segments.add(t.textValue());
                } else {
                    collect(v);
                }
            }
        } else if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                JsonNode v = arr.get(i);
                if (v instanceof TextNode t && translatable(t.textValue())) {
                    slots.add(new Object[]{arr, i});
                    segments.add(t.textValue());
                } else {
                    collect(v);
                }
            }
        }
    }

    /** 含至少一个字母/汉字，且不是 URL/邮箱/标识符样式的技术串，才值得送翻。 */
    static boolean translatable(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        String s = v.strip();
        if (s.matches("(?i)^[a-z][a-z0-9+.-]*://\\S+$") || s.matches("^[\\w.-]+@[\\w.-]+$")) {
            return false;                       // URL / 邮箱
        }
        // 纯 ASCII、无空格、且含分隔符的短串视为标识符/路径（en_US、user.name、C:\x）；
        // 单个自然语言单词（"Hello"）不含分隔符，仍会送翻
        if (s.chars().noneMatch(c -> c > 127) && !s.contains(" ")
                && s.matches("^[A-Za-z0-9]*[_\\\\/.:-][\\w\\\\/.:-]*$")) {
            return false;
        }
        return s.codePoints().anyMatch(Character::isLetter);
    }

    @Override
    public List<String> segments() {
        return segments;
    }

    @Override
    public byte[] rebuild(List<String> translated) throws Exception {
        for (int k = 0; k < slots.size(); k++) {
            Object[] slot = slots.get(k);
            if (slot[0] instanceof ObjectNode obj) {
                obj.put((String) slot[1], translated.get(k));
            } else {
                ((ArrayNode) slot[0]).set((Integer) slot[1], TextNode.valueOf(translated.get(k)));
            }
        }
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    }

    @Override
    public String outputExt() {
        return "json";
    }
}
