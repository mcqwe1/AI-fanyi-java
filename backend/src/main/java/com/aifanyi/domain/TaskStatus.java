package com.aifanyi.domain;

/**
 * 任务状态机。普通模式：PENDING → EXTRACTING_AUDIO → TRANSCRIBING → TRANSLATING →（可选 BURNING）→ DONE。
 * 知识库模式额外经过 ANALYZING_VIDEO / BUILDING_KB（阶段3）。
 */
public enum TaskStatus {
    PENDING,
    EXTRACTING_AUDIO,
    TRANSCRIBING,
    ANALYZING_VIDEO,
    BUILDING_KB,
    TRANSLATING,
    BURNING,
    DONE,
    FAILED
}
