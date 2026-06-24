import axios from 'axios'
import { Message } from 'element-ui'

const http = axios.create({
  baseURL: '/api',
  timeout: 600000
})

http.interceptors.request.use(cfg => {
  const t = localStorage.getItem('token')
  if (t) cfg.headers.Authorization = 'Bearer ' + t
  return cfg
})

function gotoLogin (msg) {
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  if (location.hash !== '#/login') location.hash = '#/login'
  Message.error(msg || '登录已过期，请重新登录')
}

http.interceptors.response.use(
  resp => {
    // 文件下载等非统一返回体，直接放行
    if (resp.config.responseType === 'blob') return resp
    const d = resp.data
    if (d && typeof d.code !== 'undefined' && d.code !== 0) {
      if (d.code === 401) {
        gotoLogin(d.msg)
      } else {
        Message.error(d.msg || '请求失败')
      }
      return Promise.reject(d)
    }
    return d
  },
  err => {
    const status = err.response && err.response.status
    if (status === 401 || status === 403) {
      // token 缺失/失效：清掉并跳登录，避免轮询无限弹 Forbidden
      gotoLogin()
    } else {
      Message.error((err.response && err.response.statusText) || err.message || '网络错误')
    }
    return Promise.reject(err)
  }
)

export default http
