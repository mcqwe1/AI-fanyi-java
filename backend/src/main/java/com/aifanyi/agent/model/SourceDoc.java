package com.aifanyi.agent.model;

import com.aifanyi.domain.MediaKind;

import java.util.List;

/**
 * ② 处理器链的完整产出：分片流 + 元数据。③ 之后所有节点只依赖本类型。
 *
 * @param chunks     分片（保持原始顺序）
 * @param sourceLang 源语言
 * @param targetLang 目标语言
 * @param kind       源媒体类型（决定 ⑨ 出口走哪条）
 * @param fullText   全文（出现次数统计用；子 Agent 看的是抽样摘要，但计数必须精确）
 */
public record SourceDoc(List<Chunk> chunks, String sourceLang, String targetLang,
                        MediaKind kind, String fullText) {

    public static SourceDoc of(List<Chunk> chunks, String sourceLang, String targetLang, MediaKind kind) {
        StringBuilder sb = new StringBuilder();
        for (Chunk c : chunks) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(c.text());
        }
        return new SourceDoc(chunks, sourceLang, targetLang, kind, sb.toString());
    }

    /** 是否带时间轴（决定产出 SRT 还是纯文本）。 */
    public boolean hasTimeline() {
        return kind != MediaKind.TEXT;
    }

    public List<String> texts() {
        return chunks.stream().map(Chunk::text).toList();
    }

    public int size() {
        return chunks.size();
    }
}
