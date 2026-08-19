/**
 * 统一翻译历史（GET /api/history/all）的展示辅助：
 * 类型角标文案、语言对（中英翻译）、相对日期（今天 14:00）、文档内容按格式渲染。
 * 工作台「最近项目」卡片与「翻译历史」页共用。
 */

const LANG_SHORT = {
  中文: '中', 简体中文: '中', 繁体中文: '繁',
  英语: '英', 英文: '英', English: '英', en: '英',
  日语: '日', 日文: '日', 韩语: '韩', 韩文: '韩',
  法语: '法', 德语: '德', 俄语: '俄', 西班牙语: '西',
  葡萄牙语: '葡', 意大利语: '意', 阿拉伯语: '阿', 泰语: '泰', 越南语: '越'
}

const TEXT_CHANNEL_LABEL = {
  text: '文本翻译',
  file: '文本翻译',
  ext_text: '划词翻译',
  ext_page: '整页翻译',
  ext_image: '图片翻译',
  ext_input: '输入框翻译'
}

/** 划词翻译（浏览器扩展）历史列表用的渠道文案，与统一历史角标共用一份定义。 */
export function extChannelLabel (channel) {
  return TEXT_CHANNEL_LABEL[channel] || '划词翻译'
}

/** 卡片左上角的类型角标文案。 */
export function cardType (h) {
  if (h.kind === 'doc') return '文档翻译'
  if (h.kind === 'text') return TEXT_CHANNEL_LABEL[h.mode] || '文本翻译'
  if (h.mode === 'AGENT') return '全能翻译'
  if (h.mode === 'KB') return '术语库翻译'
  return h.mediaType === 'AUDIO' ? '音频翻译' : '视频翻译'
}

/** 语言对短文案：中→英 = 「中英翻译」；源语言未知（auto）= 「译为英文」。 */
export function langPairText (h) {
  const s = shortLang(h.sourceLang)
  const t = shortLang(h.targetLang)
  if (s && t) return s + t + '翻译'
  if (h.targetLang) return '译为' + h.targetLang
  return '翻译'
}

function shortLang (lang) {
  if (!lang || lang === 'auto' || lang === '自动') return ''
  return LANG_SHORT[lang] || ''
}

/** 相对日期：今天 14:00 / 昨天 20:00 / 08/20 12:00。 */
export function fmtWhen (iso) {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d.getTime())) return ''
  const pad = n => String(n).padStart(2, '0')
  const hm = pad(d.getHours()) + ':' + pad(d.getMinutes())
  const now = new Date()
  const startOf = x => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const diffDays = Math.round((startOf(now) - startOf(d)) / 86400000)
  if (diffDays === 0) return '今天 ' + hm
  if (diffDays === 1) return '昨天 ' + hm
  return pad(d.getMonth() + 1) + '/' + pad(d.getDate()) + ' ' + hm
}

function escapeHtml (s) {
  return String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}

/**
 * 文档内容预览：先整体转义（防 XSS），再按格式做轻量渲染——
 * md 渲染标题/粗体/行内代码/列表符，其余格式保持纯文本（等宽与否交给 CSS 类）。
 */
export function renderDocPreview (format, text) {
  if (!text) return ''
  const esc = escapeHtml(text)
  if (format === 'md' || format === 'markdown') {
    return esc.split('\n').map(line => {
      let l = line
      const m = /^(#{1,6})\s+(.*)$/.exec(l)
      if (m) return '<h5>' + m[2] + '</h5>'
      l = l.replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
      l = l.replace(/`([^`]+)`/g, '<code>$1</code>')
      l = l.replace(/^\s*[-*]\s+/, '• ')
      return l
    }).join('\n')
  }
  return esc
}
