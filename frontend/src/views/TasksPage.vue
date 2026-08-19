<template>
  <div>
    <div class="page-head">
      <h2>翻译历史</h2>
      <p>音视频、文档、文本、全能翻译与浏览器插件的所有翻译记录都在这里。</p>
    </div>

    <el-card>
      <div class="toolbar">
        <el-radio-group v-model="kindFilter" size="small">
          <el-radio-button label="all">全部</el-radio-button>
          <el-radio-button label="media">音视频</el-radio-button>
          <el-radio-button label="agent">全能翻译</el-radio-button>
          <el-radio-button label="doc">文档</el-radio-button>
          <el-radio-button label="text">文本</el-radio-button>
          <el-radio-button label="ext">插件</el-radio-button>
        </el-radio-group>
        <el-radio-group v-model="stateFilter" size="small">
          <el-radio-button label="all">全部状态</el-radio-button>
          <el-radio-button label="running">进行中</el-radio-button>
          <el-radio-button label="done">已完成</el-radio-button>
          <el-radio-button label="failed">失败</el-radio-button>
        </el-radio-group>
        <span class="hint">共 {{ filtered.length }} 条，自动刷新</span>
      </div>

      <el-table :data="filtered" size="small" stripe :row-key="rowKey">
        <el-table-column label="类型" width="96">
          <template slot-scope="{ row }">
            <el-tag size="mini" effect="plain" :type="kindTagType(row)">{{ cardType(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="内容" min-width="200" show-overflow-tooltip>
          <template slot-scope="{ row }">{{ row.title || '（无标题）' }}</template>
        </el-table-column>
        <el-table-column label="语言" width="110">
          <template slot-scope="{ row }">{{ langPairText(row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="190">
          <template slot-scope="{ row }">
            <template v-if="row.kind === 'media'">
              <el-tag size="mini" :type="tagType(row.status)">{{ statusText(row.status) }}</el-tag>
              <el-progress v-if="!isFinal(row.status)" :percentage="row.progress || 0" :stroke-width="6" />
              <div v-if="row.status === 'FAILED'" class="err">{{ taskOf(row).errorMsg }}</div>
              <div v-if="taskOf(row).dubStatus === 'DUBBING'" class="dub-line">
                <el-tag size="mini" type="warning" effect="plain">配音中 {{ taskOf(row).dubProgress || 0 }}%</el-tag>
              </div>
              <div v-else-if="taskOf(row).dubStatus === 'FAILED'" class="dub-line">
                <el-tag size="mini" type="danger" effect="plain">配音失败</el-tag>
              </div>
            </template>
            <template v-else>
              <el-tag size="mini" :type="row.status === 'FAILED' ? 'danger' : (isDone(row) ? 'success' : 'warning')">
                {{ row.status === 'FAILED' ? '失败' : (isDone(row) ? '已完成' : '进行中 ' + (row.progress || 0) + '%') }}
              </el-tag>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="110">
          <template slot-scope="{ row }">{{ fmtWhen(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="560">
          <template slot-scope="{ row }">
            <!-- 音视频 / 全能翻译任务 -->
            <template v-if="row.kind === 'media'">
              <el-button v-if="row.status === 'DONE' && row.mediaType !== 'TEXT'" type="text" icon="el-icon-edit-outline"
                         @click="$router.push(`/editor/${row.id}`)">编辑字幕</el-button>
              <el-button v-if="row.status === 'DONE' && row.mediaType !== 'TEXT'" type="text" @click="download(row.id)">SRT</el-button>
              <el-button v-if="row.status === 'DONE'" type="text" @click="downloadTxt(row.id)">TXT</el-button>
              <el-button v-if="row.mediaType === 'VIDEO' && (row.status === 'DONE' || row.status === 'BURNING')"
                         type="text" @click="openStyle(row.id)">烧录</el-button>
              <el-button v-if="row.mediaType === 'VIDEO' && row.status === 'DONE'"
                         type="text" @click="$router.push(`/dub/${row.id}`)">
                {{ taskOf(row).dubStatus === 'DUBBING' ? '配音中…' : (taskOf(row).hasDub ? '重新配音' : '配音') }}
              </el-button>
              <el-button v-if="taskOf(row).hasVideo" type="text" @click="downloadVideo(row.id)">下载视频</el-button>
              <el-button v-if="taskOf(row).hasDub" type="text" @click="downloadDub(row.id)">下载配音</el-button>
              <el-button v-if="isFinal(row.status)" type="text" @click="retry(taskOf(row))">重试</el-button>
              <el-button type="text" class="del" @click="remove(taskOf(row))">删除</el-button>
            </template>

            <!-- 文档翻译 -->
            <template v-else-if="row.kind === 'doc'">
              <el-button v-if="row.status === 'SUCCESS'" type="text"
                         @click="$router.push(`/doc/${row.id}/compare`)">对照</el-button>
              <el-button v-if="row.status === 'SUCCESS'" type="text" @click="downloadDoc(row)">下载译文</el-button>
              <el-button type="text" class="del" @click="removeDoc(row)">删除</el-button>
            </template>

            <!-- 文本 / 插件（划词、整页、图片） -->
            <template v-else>
              <el-button type="text" @click="viewText(row)">查看</el-button>
              <el-button type="text" @click="exportText(row, 'plain')">导出译文</el-button>
              <el-button type="text" @click="exportText(row, 'pair')">导出对照</el-button>
              <el-button type="text" class="del" @click="removeText(row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!filtered.length" description="没有符合条件的记录" :image-size="80" />
    </el-card>

    <!-- 文本类记录的对照查看弹窗 -->
    <el-dialog title="翻译详情" :visible.sync="textVisible" width="760px" top="6vh">
      <div v-loading="textLoading" class="pair-box">
        <div v-for="(p, i) in textPairs" :key="i" class="pair-row">
          <div class="pair-src">{{ p.source }}</div>
          <div class="pair-tgt">{{ p.target }}</div>
        </div>
        <el-empty v-if="!textLoading && !textPairs.length" description="暂无数据" :image-size="60" />
      </div>
    </el-dialog>

    <style-dialog :visible.sync="styleVisible" :task-id="styleTaskId" @burned="fetchAll" />
  </div>
</template>

<script>
import http from '../api/http'
import StyleDialog from '../components/StyleDialog.vue'
import { statusText, isFinal, tagType } from '../constants/status'
import { blobDown } from '../utils/download'
import taskOps from '../mixins/taskOps'
import { cardType, langPairText, fmtWhen } from '../utils/history'
import { downloadText, pairsToPlain, pairsToCompare } from '../utils/textActions'

export default {
  name: 'TasksPage',
  components: { StyleDialog },
  mixins: [taskOps],
  data () {
    return {
      history: [],
      tasks: [],
      kindFilter: 'all',
      stateFilter: 'all',
      timer: null,
      styleVisible: false,
      styleTaskId: null,
      textVisible: false,
      textLoading: false,
      textPairs: []
    }
  },
  computed: {
    taskById () {
      const m = {}
      for (const t of this.tasks) m[t.id] = t
      return m
    },
    filtered () {
      return this.history.filter(h => {
        if (this.kindFilter === 'media' && !(h.kind === 'media' && h.mode !== 'AGENT')) return false
        if (this.kindFilter === 'agent' && !(h.kind === 'media' && h.mode === 'AGENT')) return false
        if (this.kindFilter === 'doc' && h.kind !== 'doc') return false
        if (this.kindFilter === 'text' && !(h.kind === 'text' && !String(h.mode).startsWith('ext_'))) return false
        if (this.kindFilter === 'ext' && !(h.kind === 'text' && String(h.mode).startsWith('ext_'))) return false
        if (this.stateFilter === 'running' && (this.isDone(h) || h.status === 'FAILED')) return false
        if (this.stateFilter === 'done' && !this.isDone(h)) return false
        if (this.stateFilter === 'failed' && h.status !== 'FAILED') return false
        return true
      })
    }
  },
  mounted () {
    this.fetchAll()
    this.timer = setInterval(this.fetchAll, 3000)
  },
  beforeDestroy () { clearInterval(this.timer) },
  methods: {
    statusText, isFinal, tagType, cardType, langPairText, fmtWhen,
    rowKey (row) { return row.kind + '-' + row.id },
    isDone (h) { return h.status === 'DONE' || h.status === 'SUCCESS' },
    kindTagType (h) {
      if (h.kind === 'doc') return 'success'
      if (h.kind === 'text') return String(h.mode).startsWith('ext_') ? 'warning' : 'info'
      return h.mode === 'AGENT' ? 'danger' : ''
    },
    taskOf (row) { return this.taskById[row.id] || {} },
    fetchAll () {
      http.get('/history/all', { params: { limit: 100 } })
        .then(r => { this.history = r.data || [] }).catch(() => {})
      http.get('/tasks').then(r => { this.tasks = r.data || [] }).catch(() => {})
    },
    download (id) { blobDown(`/tasks/${id}/srt`, `subtitle-${id}.srt`) },
    downloadTxt (id) { blobDown(`/tasks/${id}/txt`, `transcript-${id}.txt`) },
    downloadVideo (id) { blobDown(`/tasks/${id}/video`, `burned-${id}.mp4`) },
    downloadDub (id) { blobDown(`/tasks/${id}/dub-video`, `dubbed-${id}.mp4`) },
    openStyle (id) {
      this.styleTaskId = id
      this.styleVisible = true
    },
    retry (row) { this.retryTask(row, this.fetchAll) },
    remove (row) { this.removeTask(row, this.fetchAll) },
    /* ---- 文档 ---- */
    async downloadDoc (row) {
      try {
        const resp = await http.get(`/doc/${row.id}/download`, { responseType: 'blob' })
        const url = URL.createObjectURL(resp.data)
        const a = document.createElement('a')
        a.href = url
        a.download = row.title ? row.title.replace(/\.[^.]+$/, '') + '.译文' : 'translation'
        document.body.appendChild(a)
        a.click()
        document.body.removeChild(a)
        URL.revokeObjectURL(url)
      } catch (e) { /* 拦截器已提示 */ }
    },
    removeDoc (row) {
      this.$confirm(`删除文档翻译「${row.title}」？`, '删除', {
        confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
      }).then(async () => {
        await http.delete(`/doc/${row.id}`)
        this.$message.success('已删除')
        this.fetchAll()
      }).catch(() => {})
    },
    /* ---- 文本 / 插件 ---- */
    async loadTextPairs (row) {
      const r = await http.get(`/translate/history/${row.id}`)
      return (r.data && r.data.lines) || []
    },
    async viewText (row) {
      this.textVisible = true
      this.textLoading = true
      try {
        this.textPairs = await this.loadTextPairs(row)
      } catch (e) { this.textPairs = [] } finally { this.textLoading = false }
    },
    async exportText (row, mode) {
      try {
        const pairs = await this.loadTextPairs(row)
        const name = cardType(row) + '-' + row.id
        if (mode === 'plain') downloadText(`${name}-译文.txt`, pairsToPlain(pairs))
        else downloadText(`${name}-对照.txt`, pairsToCompare(pairs))
      } catch (e) { /* 拦截器已提示 */ }
    },
    removeText (row) {
      this.$confirm('删除这条翻译记录？', '删除', {
        confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
      }).then(async () => {
        await http.delete(`/translate/history/${row.id}`)
        this.$message.success('已删除')
        this.fetchAll()
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 14px; margin-bottom: 14px; flex-wrap: wrap; }
.dub-line { margin-top: 4px; }
.err { color: #f56c6c; font-size: 12px; }
.pair-box { max-height: 62vh; overflow-y: auto; }
.pair-row { padding: 8px 10px; border-bottom: 1px dashed #eef1f6; }
.pair-src { color: #8a94a6; font-size: 13px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.pair-tgt { color: #26303e; font-size: 14px; line-height: 1.8; white-space: pre-wrap; word-break: break-word; }
</style>
