import http from '../api/http'

/** 拉取 blob 并触发浏览器另存（失败时拦截器已提示，静默收尾） */
export function blobDown (url, name) {
  return http.get(url, { responseType: 'blob' }).then(resp => {
    const u = URL.createObjectURL(resp.data)
    const a = document.createElement('a')
    a.href = u; a.download = name; a.click()
    URL.revokeObjectURL(u)
  }).catch(() => {})
}
