<template>
  <div class="page">
    <div class="page-head">
      <h2>文档AI翻译</h2>
      <p>上传文档，AI 整篇翻译并保留原格式排版；支持 PDF / Word / Excel / PPT / ePub / HTML / Markdown / TXT / JSON / 字幕文件。</p>
    </div>

    <div class="body">
      <el-card class="panel">
        <div slot="header">新建文档翻译</div>
        <el-form label-width="90px">
          <el-form-item label="文档">
            <el-upload ref="uploader" drag action="#" :auto-upload="false" :limit="1"
                       :show-file-list="false" :on-change="onFileChange"
                       accept=".pdf,.epub,.html,.htm,.txt,.json,.docx,.xlsx,.pptx,.md,.markdown,.srt,.vtt,.ass">
              <template v-if="!file">
                <i class="el-icon-upload" />
                <div class="el-upload__text">拖拽文档到此处，或 <em>点击选择</em></div>
              </template>
              <div v-else class="picked">
                <i class="el-icon-document picked-icon" />
                <div class="picked-name">{{ file.name }}</div>
                <div class="picked-size">{{ prettySize(file.size) }}</div>
              </div>
            </el-upload>
            <div class="hint fmt-hint">
              支持 pdf / docx / xlsx / pptx / epub / html / txt / json / md / srt / vtt / ass，单文件上限 100MB；
              Office / epub / html / 字幕保留全部排版与结构；PDF 译文输出为 Word 文档（版式格式无法原样回填）。
            </div>
          </el-form-item>
          <el-form-item v-if="isPdf" label="PDF 处理">
            <el-radio-group v-model="pdfMode" size="small">
              <el-radio-button label="layout">保版式</el-radio-button>
              <el-radio-button label="fast">快速</el-radio-button>
            </el-radio-group>
            <div class="hint">
              <template v-if="pdfMode === 'layout'">
                先做版面分析，再按原栏宽字号把译文重排回原位，最贴近原件；较慢（实测 72 段约 93 秒）
              </template>
              <template v-else>
                内置引擎直接回填译文，排版从简；实测同一份 72 段 PDF 只要 13 秒，快约 7 倍
              </template>
            </div>
          </el-form-item>
          <el-form-item label="目标语言">
            <el-select v-model="targetLang" filterable style="width:100%">
              <el-option-group v-for="g in langGroups" :key="g.label" :label="g.label">
                <el-option v-for="l in g.langs" :key="l" :label="l" :value="l" />
              </el-option-group>
            </el-select>
          </el-form-item>
          <el-form-item label="翻译风格">
            <el-switch v-model="styleEnabled" />
            <template v-if="styleEnabled">
              <div class="style-presets">
                <el-tag v-for="p in stylePresets" :key="p.label" size="small" effect="plain"
                        class="style-tag" @click="stylePrompt = p.prompt">{{ p.label }}</el-tag>
              </div>
              <el-input v-model="stylePrompt" type="textarea" :rows="2" maxlength="500" show-word-limit
                        placeholder="描述期望的翻译风格，如：正式书面、口语化、古风文雅…" />
            </template>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="submitting" :disabled="!file" @click="submit">开始翻译</el-button>
            <el-button v-if="file" @click="clearFile">移除文件</el-button>
            <span class="hint">提交后台异步翻译，可关闭页面稍后回来下载</span>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card class="panel">
        <div slot="header">
          翻译任务
          <el-button style="float:right;padding:3px 0" type="text" icon="el-icon-refresh"
                     @click="fetchList">刷新</el-button>
        </div>
        <el-table :data="list" size="small" stripe>
          <el-table-column prop="id" label="ID" width="55" />
          <el-table-column label="文档" min-width="180" show-overflow-tooltip>
            <template slot-scope="{ row }">
              <i class="el-icon-document" /> {{ row.filename }}
            </template>
          </el-table-column>
          <el-table-column label="格式" width="110">
            <template slot-scope="{ row }">
              {{ row.format }}<template v-if="row.outputFormat && row.outputFormat !== row.format"> → {{ row.outputFormat }}</template>
            </template>
          </el-table-column>
          <el-table-column prop="targetLang" label="目标语言" width="85" />
          <el-table-column label="状态" width="190">
            <template slot-scope="{ row }">
              <el-progress v-if="row.status === 'RUNNING' || row.status === 'PENDING'"
                           :percentage="row.progress" :stroke-width="14" text-inside />
              <el-tag v-else-if="row.status === 'SUCCESS'" type="success" size="small">完成</el-tag>
              <el-tooltip v-else :content="row.errorMsg || '失败'" placement="top">
                <el-tag type="danger" size="small">失败</el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="75">
            <template slot-scope="{ row }">
              <span v-if="row.elapsedMs">{{ (row.elapsedMs / 1000).toFixed(0) }}s</span>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="145">
            <template slot-scope="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="210">
            <template slot-scope="{ row }">
              <el-button v-if="row.status === 'SUCCESS'" type="text"
                         icon="el-icon-download" @click="download(row)">下载译文</el-button>
              <el-button v-if="row.status === 'SUCCESS'" type="text"
                         icon="el-icon-notebook-2" @click="$router.push(`/doc/${row.id}/compare`)">对照</el-button>
              <el-button type="text" class="del" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="warnRows.length" class="warn-tip">
          <i class="el-icon-warning-outline" />
          {{ warnRows.map(r => '#' + r.id).join('、') }} 有部分段落疑似未翻译（已保留原文），必要时可重新提交
        </div>
      </el-card>
    </div>
  </div>
</template>

<script>
import http from '../api/http'
import { LANG_GROUPS } from '../constants/langs'
import stylePresetsMixin from '../mixins/stylePresets'

export default {
  name: 'DocMode',
  mixins: [stylePresetsMixin],
  data () {
    return {
      langGroups: LANG_GROUPS,
      targetLang: '中文',
      file: null,
      pdfMode: 'layout',
      submitting: false,
      list: [],
      timer: null
    }
  },
  computed: {
    isPdf () {
      return !!this.file && /\.pdf$/i.test(this.file.name || '')
    },
    warnRows () {
      return this.list.filter(r => r.status === 'SUCCESS' && r.untranslatedSegments > 0)
    }
  },
  mounted () {
    this.fetchList()
  },
  beforeDestroy () {
    this.stopPolling()
  },
  methods: {
    onFileChange (f) {
      const name = f.name || ''
      if (!/\.(pdf|epub|html?|txt|json|docx|xlsx|pptx|md|markdown|srt|vtt|ass)$/i.test(name)) {
        this.$refs.uploader.clearFiles()
        return this.$message.warning('不支持的文档格式')
      }
      if (f.size > 100 * 1024 * 1024) {
        this.$refs.uploader.clearFiles()
        return this.$message.warning('文件过大（上限 100MB）')
      }
      this.file = f.raw
      this.file.name = name
    },
    clearFile () {
      this.file = null
      this.$refs.uploader.clearFiles()
    },
    async submit () {
      if (!this.file) return
      this.submitting = true
      try {
        const fd = new FormData()
        fd.append('file', this.file)
        fd.append('targetLang', this.targetLang)
        fd.append('stylePrompt', this.styleEnabled ? this.stylePrompt.trim() : '')
        if (this.isPdf) fd.append('pdfMode', this.pdfMode)
        await http.post('/doc/upload', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
        this.$message.success('已提交，后台翻译中')
        this.clearFile()
        this.fetchList()
      } catch (e) { /* 拦截器已提示 */ } finally { this.submitting = false }
    },
    async fetchList () {
      try {
        const r = await http.get('/doc/list')
        this.list = r.data || []
        const active = this.list.some(t => t.status === 'RUNNING' || t.status === 'PENDING')
        if (active) this.startPolling()
        else this.stopPolling()
      } catch (e) { this.stopPolling() }
    },
    startPolling () {
      if (this.timer) return
      this.timer = setInterval(this.fetchList, 2000)
    },
    stopPolling () {
      if (this.timer) {
        clearInterval(this.timer)
        this.timer = null
      }
    },
    async download (row) {
      try {
        const resp = await http.get(`/doc/${row.id}/download`, { responseType: 'blob' })
        const url = URL.createObjectURL(resp.data)
        const a = document.createElement('a')
        a.href = url
        a.download = row.outputName || `translation.${row.outputFormat || row.format}`
        a.click()
        URL.revokeObjectURL(url)
      } catch (e) { /* 拦截器已提示 */ }
    },
    remove (row) {
      this.$confirm(`确定删除任务「${row.filename}」吗？译文文件将一并删除。`, '删除', {
        confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
      }).then(async () => {
        try {
          await http.delete(`/doc/${row.id}`)
          this.$message.success('已删除')
          this.fetchList()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    prettySize (n) {
      if (n < 1024) return n + ' B'
      if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
      return (n / 1024 / 1024).toFixed(1) + ' MB'
    },
    formatTime (t) {
      if (!t) return ''
      return String(t).replace('T', ' ').slice(0, 16)
    }
  }
}
</script>

<style scoped>
.body { max-width: 980px; margin: 0 auto; display: flex; flex-direction: column; gap: 20px; }
.hint { margin-left: 12px; color: #999; font-size: 12px; }
.fmt-hint { margin: 6px 0 0; line-height: 1.6; }
.picked { padding: 28px 12px; }
.picked-icon { font-size: 40px; color: var(--brand-deep, #5b7cfa); }
.picked-name { margin-top: 10px; font-size: 14px; color: #303133; word-break: break-all; }
.picked-size { margin-top: 4px; font-size: 12px; color: #a0aec0; }
.warn-tip { margin-top: 10px; color: #e6a23c; font-size: 12px; }
.del { color: #f56c6c; }
</style>
