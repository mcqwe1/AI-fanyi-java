<template>
  <div class="page">
    <div class="page-head compare-head">
      <div>
        <h2>
          <el-button type="text" icon="el-icon-back" class="back" @click="$router.push('/doc')">返回</el-button>
          原文 · 译文对照
        </h2>
        <p v-if="doc">
          {{ doc.filename }}（{{ doc.format }}）→ {{ doc.targetLang }}
          <template v-if="doc.model">· 模型 {{ doc.model }}</template>
          <template v-if="hasSegments">
            · 共 {{ doc.segments.length }} 段<template v-if="untransCount">，{{ untransCount }} 段疑似未翻译</template>
          </template>
        </p>
      </div>
      <div class="tools">
        <!-- 版式对照：PDF/HTML 用浏览器原生查看器并排渲染，就是「双击打开文件」的样子 -->
        <!-- BabelDOC 翻译的 PDF 没有段级数据（hasSegments=false），只提供版式对照 -->
        <el-radio-group v-if="canPreview && hasSegments" v-model="viewMode" size="small">
          <el-radio-button label="layout">版式对照</el-radio-button>
          <el-radio-button label="text">文本对照</el-radio-button>
        </el-radio-group>
        <template v-if="viewMode === 'text'">
          <el-input v-model="keyword" size="small" clearable prefix-icon="el-icon-search"
                    placeholder="搜索原文或译文" style="width:220px" />
          <el-checkbox v-if="untransCount" v-model="onlyUntranslated" class="only-un">只看未翻译</el-checkbox>
        </template>
      </div>
    </div>

    <div v-if="loading" class="state"><i class="el-icon-loading" /> 加载对照数据…</div>
    <div v-else-if="error" class="state error">{{ error }}</div>

    <!-- 版式对照：左原文 / 右译文，浏览器原生渲染（PDF 查看器 / HTML 页面） -->
    <div v-else-if="viewMode === 'layout'" class="layout-body">
      <div class="layout-pane">
        <div class="pane-head">原文</div>
        <iframe :src="fileUrl('source')" class="pane-frame" title="原文" />
      </div>
      <div class="layout-pane">
        <div class="pane-head">译文</div>
        <iframe :src="fileUrl('output')" class="pane-frame" title="译文" />
      </div>
    </div>

    <div v-else class="body">
      <div class="col-heads">
        <div class="col-head">原文</div>
        <div class="col-head">译文</div>
      </div>
      <div v-for="s in shown" :key="s.idx" class="row" :class="{ un: s.untranslated }">
        <div class="no">{{ s.idx }}</div>
        <div class="cell src">{{ s.source }}</div>
        <div class="cell dst">
          {{ s.target }}
          <el-tag v-if="s.untranslated" size="mini" type="warning" class="un-tag">与原文相同</el-tag>
        </div>
      </div>
      <div v-if="!shown.length" class="state">
        {{ hasSegments ? '没有匹配的段落' : '该任务没有分段对照数据' }}
      </div>
    </div>
  </div>
</template>

<script>
import http from '../api/http'

/** 浏览器能原生内嵌渲染的格式：PDF 用内置查看器，HTML 直接展示。 */
const PREVIEWABLE = ['pdf', 'html', 'htm']

export default {
  name: 'DocCompare',
  data () {
    return {
      doc: null,
      loading: true,
      error: '',
      keyword: '',
      onlyUntranslated: false,
      viewMode: 'text'
    }
  },
  computed: {
    hasSegments () {
      return !!(this.doc && this.doc.segments && this.doc.segments.length)
    },
    canPreview () {
      // 版式对照要求原文与译文都能内嵌渲染；pdf→pdf、html→html 满足，
      // 旧任务 pdf→docx（降级）时译文不可渲染，只给文本对照
      if (!this.doc) return false
      const outOk = !this.doc.outputFormat || PREVIEWABLE.includes(this.doc.outputFormat)
      return PREVIEWABLE.includes(this.doc.format) && outOk
    },
    untransCount () {
      return this.doc ? this.doc.segments.filter(s => s.untranslated).length : 0
    },
    shown () {
      if (!this.doc) return []
      const kw = this.keyword.trim().toLowerCase()
      return this.doc.segments.filter(s => {
        if (this.onlyUntranslated && !s.untranslated) return false
        if (!kw) return true
        return s.source.toLowerCase().includes(kw) || s.target.toLowerCase().includes(kw)
      })
    }
  },
  async mounted () {
    const id = this.$route.params.id
    try {
      const [seg, item] = await Promise.all([
        http.get(`/doc/${id}/segments`),
        http.get(`/doc/${id}`).catch(() => null)
      ])
      this.doc = seg.data
      if (item && item.data) this.doc.outputFormat = item.data.outputFormat
      // PDF 等可版式渲染的文档默认进「双击打开文件」式对照
      if (this.canPreview) this.viewMode = 'layout'
    } catch (e) {
      this.error = (e.response && e.response.data && e.response.data.msg) || (e && e.msg) || '加载失败'
    } finally {
      this.loading = false
    }
  },
  methods: {
    /** iframe 带不了 Authorization 头，凭证走 access_token 查询参数。 */
    fileUrl (kind) {
      const token = encodeURIComponent(localStorage.getItem('token') || '')
      return `/api/doc/${this.$route.params.id}/file?kind=${kind}&access_token=${token}`
    }
  }
}
</script>

<style scoped>
.compare-head { display: flex; justify-content: space-between; align-items: flex-end; flex-wrap: wrap; gap: 10px; }
.back { font-size: 14px; margin-right: 4px; }
.tools { display: flex; align-items: center; gap: 12px; padding-bottom: 4px; }
.only-un { white-space: nowrap; }
.state { text-align: center; color: #909399; padding: 60px 0; }
.state.error { color: #f56c6c; }

/* ---- 版式对照 ---- */
.layout-body {
  display: grid; grid-template-columns: 1fr 1fr; gap: 14px;
  height: calc(100vh - 190px); min-height: 480px;
}
.layout-pane {
  display: flex; flex-direction: column; min-width: 0;
  background: #fff; border: 1px solid #ebeef5; border-radius: 8px; overflow: hidden;
}
.pane-head {
  flex: none; padding: 9px 16px; font-weight: 600; color: #606266;
  background: #f5f7fa; border-bottom: 1px solid #ebeef5; font-size: 13px;
}
.pane-frame { flex: 1; width: 100%; border: 0; background: #525659; }

.body { max-width: 1200px; margin: 0 auto; background: #fff; border: 1px solid #ebeef5; border-radius: 6px; overflow: hidden; }
.col-heads { display: flex; background: #f5f7fa; border-bottom: 1px solid #ebeef5; font-weight: 600; color: #606266; }
.col-head { flex: 1; padding: 10px 16px 10px 52px; }
.col-head + .col-head { padding-left: 16px; border-left: 1px solid #ebeef5; }
.row { display: flex; border-bottom: 1px solid #f2f4f8; font-size: 14px; line-height: 1.7; }
.row:hover { background: #f9fafc; }
.row.un { background: #fdf6ec; }
.no { width: 36px; flex: none; padding: 10px 0; text-align: center; color: #c0c4cc; font-size: 12px; }
.cell { flex: 1; padding: 10px 16px; white-space: pre-wrap; word-break: break-word; color: #303133; }
.cell.dst { border-left: 1px solid #ebeef5; }
.un-tag { margin-left: 8px; }
</style>
