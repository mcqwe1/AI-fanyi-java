package com.aifanyi.domain;

/**
 * 任务模式。
 *
 * <p>2026-08 删除了 KB（术语库AI视频翻译）：它唯一的独有能力「把抽出的新词沉淀进指定术语库」
 * 已由全能 AI 翻译的「新词存入」接管，其余（抽词、联网核实、套用术语库）全被覆盖且更强。
 * <p>历史任务的 mode 列里仍存着字符串 "KB"，但本枚举<b>从不用于解析 DB 值</b>
 * （全项目只做 {@code TaskMode.X.name().equalsIgnoreCase(mode)} 比较），
 * 老记录照常展示，分发时按普通模式处理——它们早已是 DONE 状态。
 */
public enum TaskMode {
    /** 普通 AI 音视频翻译 */
    NORMAL,
    /** 全能 AI 翻译（即 Agent 模式） */
    AGENT
}
