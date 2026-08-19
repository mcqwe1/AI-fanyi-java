/**
 * 翻译结果的通用文本操作（2026-08 需求改版）：复制、网页查看、导出译文/对照。
 * 文本AI翻译与全能AI翻译共用。
 */

function escapeHtml (s) {
  return String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}

/** 复制到剪贴板；clipboard API 不可用（非 https 等）时降级 execCommand。 */
export async function copyText (text) {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch (e) {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    let ok = false
    try { ok = document.execCommand('copy') } catch (e2) { ok = false }
    document.body.removeChild(ta)
    return ok
  }
}

/** 在新标签页打开一个干净排版的译文页面（网页查看译文）。 */
export function openTextPage (title, text) {
  const html = '<!doctype html><html lang="zh"><head><meta charset="utf-8">'
    + '<title>' + escapeHtml(title) + '</title>'
    + '<style>body{max-width:820px;margin:40px auto;padding:0 24px 60px;'
    + 'font:15px/1.9 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif;color:#26303e;'
    + 'white-space:pre-wrap;word-break:break-word;background:#fafbfd}'
    + 'h1{font-size:17px;color:#1f2940;border-bottom:1px solid #e8ecf3;padding-bottom:12px}</style>'
    + '</head><body><h1>' + escapeHtml(title) + '</h1>' + escapeHtml(text) + '</body></html>'
  const url = URL.createObjectURL(new Blob([html], { type: 'text/html' }))
  window.open(url, '_blank')
  // 给新页面留足加载时间再回收 URL
  setTimeout(() => URL.revokeObjectURL(url), 60000)
}

/** 下载纯文本文件（带 BOM，记事本/Excel 打开不乱码）。 */
export function downloadText (filename, content) {
  const blob = new Blob(['﻿' + content], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

/** [{source,target}] → 纯译文文本。 */
export function pairsToPlain (pairs) {
  return (pairs || []).map(p => p.target || '').join('\n')
}

/** [{source,target}] → 「原文\n译文\n」交替的对照文本。 */
export function pairsToCompare (pairs) {
  return (pairs || []).map(p => (p.source || '') + '\n' + (p.target || '')).join('\n\n')
}
