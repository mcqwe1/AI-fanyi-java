import http from '../api/http'
import { STYLE_PRESETS } from '../constants/styles'

/**
 * 翻译风格预设：内置常量 + 用户在「设置」里保存的自定义风格。
 * 三个翻译模式共用；组件里 this.stylePresets 直接绑到风格标签列表即可。
 * 同时托管 styleEnabled/stylePrompt 表单状态：设置页存过默认风格则自动带出
 * （任务里可临时改/关，只影响本次）。
 */
export default {
  data () {
    return {
      stylePresets: STYLE_PRESETS.slice(),
      styleEnabled: false,
      stylePrompt: ''
    }
  },
  created () {
    http.get('/style-presets').then(r => {
      const custom = (r.data || []).filter(p => p.label && p.prompt)
      this.stylePresets = [...STYLE_PRESETS, ...custom]
    }).catch(() => {})
    this.loadDefaultStyle()
  },
  methods: {
    loadDefaultStyle () {
      http.get('/settings').then(r => {
        const s = r.data && r.data.stylePrompt
        if (s) {
          this.styleEnabled = true
          this.stylePrompt = s
        }
      }).catch(() => {})
    }
  }
}
