package com.aifanyi.agent.model;

/**
 * ② 处理器链产出的统一中间态：一片文本 + 可选时间轴坐标。
 * <p>这是「③ 之后所有节点与文件类型彻底解耦」的关键——视频/音频/文本三条链各自实现
 * 到这里为止，往后的场景推测、抽词、仲裁、翻译全部只认 Chunk，不知道源文件是什么。
 *
 * @param index   序号（从 0 起，即原始顺序）
 * @param text    文本内容
 * @param startMs 起始毫秒；文本链为 null（无时间轴）
 * @param endMs   结束毫秒；文本链为 null
 */
public record Chunk(int index, String text, Long startMs, Long endMs) {

    /** 有时间轴的分片（视频/音频链）。 */
    public static Chunk timed(int index, String text, long startMs, long endMs) {
        return new Chunk(index, text, startMs, endMs);
    }

    /** 无时间轴的分片（文本链）。 */
    public static Chunk plain(int index, String text) {
        return new Chunk(index, text, null, null);
    }

    public boolean hasTimeline() {
        return startMs != null && endMs != null;
    }
}
