package com.aifanyi.domain;

/**
 * 任务状态机。普通模式：PENDING → EXTRACTING_AUDIO → TRANSCRIBING → TRANSLATING →（可选 BURNING）→ DONE。
 * 知识库模式额外经过 BUILDING_KB；Agent 模式额外经过 INFERRING_SCENE → AGENT_TERMS。
 * 配音（TTS）不进主状态机——它是 DONE 任务的附加操作，走 TranslationTask.dubStatus 独立状态，
 * 成败不影响翻译成果与主 status。
 * <p>新增运行中状态时，前端 constants/status.js 的 STATUS_TEXT 必须同步添加，
 * 否则界面会裸露枚举名。（StartupTaskRecovery 已改为黑名单判定，无需再登记。）
 */
public enum TaskStatus {
    PENDING,
    EXTRACTING_AUDIO,
    TRANSCRIBING,
    BUILDING_KB,
    /** Agent ③：场景推测（在档案池里匹配领域） */
    INFERRING_SCENE,
    /** Agent ④⑤⑦：子 Agent 扇出抽词 + 仲裁 + 术语入库 */
    AGENT_TERMS,
    TRANSLATING,
    BURNING,
    DONE,
    FAILED
}
