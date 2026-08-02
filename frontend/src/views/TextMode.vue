<template>
  <div class="page">
    <div class="page-head">
      <h2>文本AI翻译</h2>
      <p>输入或粘贴文本，AI 自动翻译，支持多种语言互译；历史可导出 TXT / 双语对照。</p>
    </div>

    <div class="body">
      <el-card class="panel">
        <div slot="header">新建文本翻译</div>
        <el-form label-width="90px">
          <el-form-item label="目标语言">
            <el-select v-model="targetLang" filterable style="width:100%">
              <el-option-group v-for="g in langGroups" :key="g.label" :label="g.label">
                <el-option v-for="l in g.langs" :key="l" :label="l" :value="l" />
              </el-option-group>
            </el-select>
          </el-form-item>
          <el-form-item label="原文">
            <el-input ref="sourceInput" v-model="text" type="textarea"
                      :autosize="{ minRows: 10, maxRows: 20 }" maxlength="100000" show-word-limit
                      placeholder="粘贴任意类型的文本（文章 / 对话 / 字幕 / 无换行长文均可）" />
            <div class="upload-row">
              <el-upload action="#" :auto-upload="false" :limit="1" :show-file-list="false"
                         :on-change="onFileChange" accept=".txt,.md,.markdown,.srt,.vtt">
                <el-button size="small" icon="el-icon-upload2">上传文本文件</el-button>
              </el-upload>
              <span v-if="uploadName" class="file-name">
                <i class="el-icon-document" /> {{ uploadName }}
                <el-button type="text" class="del" @click="clearUpload">移除</el-button>
              </span>
              <span class="hint">支持 txt / md / srt / vtt（自动识别 UTF-8/GBK）；srt/vtt 只翻字幕、保留时间轴。</span>
            </div>
          </el-form-item>
          <el-form-item label="翻译风格">
            <el-switch v-model="styleEnabled" />
            <template v-if="styleEnabled">
              <div class="style-presets">
                <el-tag v-for="p in stylePresets" :key="p.label" size="small" effect="plain"
                        class="style-tag" @click="stylePrompt = p.prompt">{{ p.label }}</el-tag>
              </div>
              <el-input v-model="stylePrompt" type="textarea" :rows="2" maxlength="500" show-word-limit
                        placeholder="描述期望的翻译风格，如：古风文雅、网络流行语、正式书面…" />
            </template>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="translating" @click="submit">开始翻译</el-button>
            <span class="hint">长文本可能需要几分钟，请勿关闭页面</span>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card v-if="result" class="panel">
        <div slot="header" class="result-header">
          <el-radio-group v-model="view" size="small">
            <el-radio-button label="plain">纯译文</el-radio-button>
            <el-radio-button label="pair">双语对照</el-radio-button>
          </el-radio-group>
          <span class="ops">
            <el-button size="small" icon="el-icon-document-copy" @click="copyResult">复制译文</el-button>
            <el-button v-if="result.filename" size="small" type="primary" icon="el-icon-download"
                       @click="downloadTranslatedFile">下载译文文件（{{ result.format }}）</el-button>
            <el-button size="small" icon="el-icon-download" @click="exportTxt(result, 'plain')">导出译文</el-button>
            <el-button size="small" icon="el-icon-download" @click="exportTxt(result, 'pair')">导出对照</el-button>
          </span>
        </div>
        <div class="meta">
          <span>模型：{{ result.model }}</span>
          <span>耗时：{{ (result.elapsedMs / 1000).toFixed(1) }}s</span>
          <span v-if="result.untranslatedLines > 0" class="warn">
            {{ result.untranslatedLines }} 行疑似未翻译（保留原文），可稍后重译
          </span>
        </div>
        <pre v-if="view === 'plain'" class="plain">{{ result.plainTarget }}</pre>
        <div v-else class="pairs">
          <div v-for="(l, i) in result.lines" :key="i" class="pair-row">
            <div class="src">{{ l.source || ' ' }}</div>
            <div class="tgt">{{ l.target || ' ' }}</div>
          </div>
        </div>
      </el-card>

      <el-card class="panel">
        <div slot="header">翻译历史</div>
        <el-table :data="history" size="small" stripe>
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column label="时间" width="150">
            <template slot-scope="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="targetLang" label="目标语言" width="90" />
          <el-table-column prop="preview" label="原文摘要" show-overflow-tooltip />
          <el-table-column prop="model" label="模型" width="160" show-overflow-tooltip />
          <el-table-column label="操作" width="330">
            <template slot-scope="{ row }">
              <el-button type="text" @click="viewHistory(row.id)">查看</el-button>
              <el-button type="text" @click="exportHistory(row.id, 'plain')">导出译文</el-button>
              <el-button type="text" @click="exportHistory(row.id, 'pair')">导出对照</el-button>
              <el-button type="text" @click="retranslate(row.id)">重译</el-button>
              <el-button type="text" class="del" @click="removeHistory(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>
  </div>
</template>

<script>
import http from '../api/http'
import { LANG_GROUPS } from '../constants/langs'
import stylePresetsMixin from '../mixins/stylePresets'

export default {
  name: 'TextMode',
  mixins: [stylePresetsMixin],
  data () {
    return {
      langGroups: LANG_GROUPS,
      targetLang: '中文',
      text: '',
      translating: false,
      result: null,
      view: 'plain',
      history: [],
      uploadRaw: null,        // 上传的原始文件对象
      uploadName: '',         // 上传文件名
      uploadContent: ''       // 上传文件原文（用于 srt 重组时保留时间轴）
    }
  },
  mounted () {
    this.fetchHistory()
  },
  methods: {
    onFileChange (file) {
      const name = file.name || ''
      if (!/\.(txt|md|markdown|srt|vtt)$/i.test(name)) {
        return this.$message.warning('仅支持 txt / md / srt / vtt 文本文件')
      }
      this.uploadRaw = file.raw
      this.uploadName = name
      // 读一份内容到 text 框预览（srt 也原样展示）
      const reader = new FileReader()
      reader.onload = e => { this.uploadContent = e.target.result; this.text = e.target.result }
      reader.readAsText(file.raw)
    },
    clearUpload () {
      this.uploadRaw = null
      this.uploadName = ''
      this.uploadContent = ''
    },
    async submit () {
      // 有附件走文件翻译接口（能保 srt 格式），否则走纯文本
      if (this.uploadRaw) return this.submitFile()
      if (!this.text.trim()) return this.$message.warning('请输入要翻译的文本')
      this.translating = true
      this.result = null
      try {
        const r = await http.post('/translate/text', {
          text: this.text,
          targetLang: this.targetLang,
          stylePrompt: this.styleEnabled ? this.stylePrompt.trim() : ''
        })
        this.result = r.data
        this.view = 'plain'
        this.fetchHistory()
      } catch (e) { /* 拦截器已提示 */ } finally { this.translating = false }
    },
    async submitFile () {
      this.translating = true
      this.result = null
      try {
        const fd = new FormData()
        fd.append('file', this.uploadRaw)
        fd.append('targetLang', this.targetLang)
        fd.append('stylePrompt', this.styleEnabled ? this.stylePrompt.trim() : '')
        const r = await http.post('/translate/file', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
        this.result = r.data
        this.view = 'plain'
        this.fetchHistory()
      } catch (e) { /* 拦截器已提示 */ } finally { this.translating = false }
    },
    /** 下载同格式译文文件：srt/vtt 用原文件时间轴 + 译文行重组；txt/md 直接纯译文 */
    downloadTranslatedFile () {
      const r = this.result
      if (!r) return
      let content
      if (r.format === 'srt' || r.format === 'vtt') {
        content = this.rebuildSubtitle(this.uploadContent, r.lines, r.format)
      } else {
        content = r.plainTarget
      }
      const blob = new Blob([content], { type: 'text/plain;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = r.filename || 'translation.txt'
      a.click()
      URL.revokeObjectURL(url)
    },
    /** 用原始 srt/vtt 的序号+时间轴，把每条字幕文本换成译文（按出现顺序对应译文行）。 */
    rebuildSubtitle (original, lines, format) {
      const targets = (lines || []).map(l => l.target != null ? l.target : '')
      const src = (original || '').replace(/\r\n/g, '\n').replace(/\r/g, '\n')
      const rows = src.split('\n')
      const out = []
      let ti = 0
      for (let i = 0; i < rows.length; i++) {
        const t = rows[i].trim()
        const isNum = /^\d+$/.test(t)
        const isTime = t.includes('-->')
        const isHeader = t.toUpperCase() === 'WEBVTT'
        if (t === '' || isNum || isTime || isHeader) {
          out.push(rows[i])           // 结构行原样保留
        } else {
          out.push(ti < targets.length ? targets[ti] : rows[i])
          ti++
        }
      }
      return out.join('\n')
    },
    fetchHistory () {
      http.get('/translate/history').then(r => { this.history = r.data }).catch(() => {})
    },
    async fetchDetail (id) {
      const r = await http.get(`/translate/history/${id}`)
      return r.data
    },
    async viewHistory (id) {
      try {
        this.result = await this.fetchDetail(id)
        this.view = 'plain'
        window.scrollTo({ top: 0, behavior: 'smooth' })
      } catch (e) { /* 拦截器已提示 */ }
    },
    async exportHistory (id, kind) {
      try {
        this.exportTxt(await this.fetchDetail(id), kind)
      } catch (e) { /* 拦截器已提示 */ }
    },
    /** 导出 txt：plain=纯译文；pair=原文/译文逐行交替（空行保持段落） */
    exportTxt (data, kind) {
      let content
      if (kind === 'plain') {
        content = data.plainTarget || ''
      } else {
        content = (data.lines || []).map(l => {
          if (!l.source.trim() && !l.target.trim()) return ''
          return l.source + '\n' + l.target + '\n'
        }).join('\n')
      }
      // 带 BOM，避免 Windows 记事本打开中文乱码
      const blob = new Blob(['﻿' + content], { type: 'text/plain;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `文本翻译-${data.id}-${kind === 'plain' ? '译文' : '对照'}.txt`
      a.click()
      URL.revokeObjectURL(url)
    },
    async retranslate (id) {
      try {
        const d = await this.fetchDetail(id)
        this.text = d.sourceText
        this.targetLang = d.targetLang
        this.styleEnabled = !!d.stylePrompt
        this.stylePrompt = d.stylePrompt || ''
        window.scrollTo({ top: 0, behavior: 'smooth' })
        this.$message.info('原文已回填，可修改目标语言/风格后重新翻译')
      } catch (e) { /* 拦截器已提示 */ }
    },
    removeHistory (row) {
      this.$confirm(`确定删除这条翻译历史（${row.preview || row.id}）吗？`, '删除', {
        confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
      }).then(async () => {
        try {
          await http.delete(`/translate/history/${row.id}`)
          this.$message.success('已删除')
          if (this.result && this.result.id === row.id) this.result = null
          this.fetchHistory()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    async copyResult () {
      const t = this.result ? (this.result.plainTarget || '') : ''
      try {
        await navigator.clipboard.writeText(t)
        this.$message.success('已复制')
      } catch (e) {
        const ta = document.createElement('textarea')
        ta.value = t
        document.body.appendChild(ta)
        ta.select()
        document.execCommand('copy')
        ta.remove()
        this.$message.success('已复制')
      }
    },
    formatTime (t) {
      if (!t) return ''
      return String(t).replace('T', ' ').slice(0, 16)
    }
  }
}
</script>

<style scoped>
.body { max-width: 900px; margin: 0 auto; display: flex; flex-direction: column; gap: 20px; }
.hint { margin-left: 12px; color: #999; font-size: 12px; }
.upload-row { display: flex; align-items: center; gap: 12px; margin-top: 8px; flex-wrap: wrap; }
.file-name { color: #606266; font-size: 13px; }
.result-header { display: flex; align-items: center; justify-content: space-between; }
.meta { color: #888; font-size: 12px; margin-bottom: 10px; display: flex; gap: 16px; }
.meta .warn { color: #e6a23c; }
.plain {
  margin: 0; padding: 12px; background: #fafafa; border-radius: 4px;
  white-space: pre-wrap; word-break: break-word; font-family: inherit;
  font-size: 14px; line-height: 1.7; max-height: 60vh; overflow: auto;
}
.pairs { max-height: 60vh; overflow: auto; }
.pair-row { display: flex; gap: 12px; padding: 4px 0; border-bottom: 1px dashed #eee; }
.pair-row .src { flex: 1; color: #909399; word-break: break-word; }
.pair-row .tgt { flex: 1; color: #303133; word-break: break-word; }
</style>
