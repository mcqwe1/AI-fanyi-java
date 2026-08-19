// 任务状态 → 中文文案 / 标签颜色 / 终态判断，各任务列表页共用
// 新增后端 TaskStatus 枚举值时必须同步加进来，否则界面裸露英文枚举名
export const STATUS_TEXT = {
  PENDING: '排队中', EXTRACTING_AUDIO: '提取音频', TRANSCRIBING: '语音转文字',
  // BUILDING_KB 是已删除的 KB 模式遗留状态，保留映射避免老任务记录裸露枚举名
  BUILDING_KB: '抽取术语', INFERRING_SCENE: '识别领域', AGENT_TERMS: '专家抽词',
  TRANSLATING: 'AI 翻译中',
  BURNING: '烧录字幕', DONE: '完成', FAILED: '失败'
}

export function statusText (s) { return STATUS_TEXT[s] || s }
export function isFinal (s) { return s === 'DONE' || s === 'FAILED' }
export function tagType (s) { return s === 'DONE' ? 'success' : (s === 'FAILED' ? 'danger' : 'info') }
