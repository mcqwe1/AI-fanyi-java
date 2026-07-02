<template>
  <div class="page">
    <div class="topbar">
      <el-button type="text" style="color:#fff" icon="el-icon-back" @click="$router.push('/')">返回</el-button>
      <span>AI 知识库翻译（术语表）</span>
      <span />
    </div>

    <div class="body">
      <!-- 项目选择条 -->
      <el-card class="panel">
        <div class="proj-bar">
          <span class="lbl">系列项目</span>
          <el-select v-model="projectId" placeholder="选择系列项目" style="width:260px" @change="onProjectChange">
            <el-option v-for="p in projects" :key="p.id"
                       :label="`${p.name}（${p.sourceLang}→${p.targetLang}，${p.termCount}词）`" :value="p.id" />
          </el-select>
          <el-button icon="el-icon-plus" @click="newProjVisible = true">新建项目</el-button>
          <el-button v-if="projectId" icon="el-icon-delete" class="del" @click="removeProject">删除项目</el-button>
          <span class="hint">一系列视频共用一张术语表，专名（人名等）译法统一。</span>
        </div>
      </el-card>

      <div v-if="projectId" class="grid">
        <!-- 左：新建任务 -->
        <el-card class="panel">
          <div slot="header">新建翻译任务（KB 模式）</div>
          <el-form label-width="80px">
            <el-form-item label="视频">
              <el-upload action="#" :auto-upload="false" :limit="1" :on-change="onFileChange"
                         :on-remove="onRemove" :file-list="fileList" accept="video/*">
                <el-button icon="el-icon-upload2">选择视频</el-button>
              </el-upload>
            </el-form-item>
            <el-form-item label="语音识别">
              <el-select v-model="asrProvider" style="width:100%">
                <el-option label="Groq（推荐）" value="groq" />
                <el-option label="Groq Turbo" value="groq-turbo" />
                <el-option-group label="本地 Whisper">
                  <el-option label="本地 base" value="local-base" />
                  <el-option label="本地 small" value="local-small" />
                  <el-option label="本地 medium" value="local-medium" />
                  <el-option label="本地 large-v3" value="local-large-v3" />
                </el-option-group>
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-checkbox v-model="bilingual">双语字幕</el-checkbox>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="submitting" @click="submit">开始翻译</el-button>
              <span class="hint">流程：转写 → Gemini 联网抽术语入库 → 套术语表翻译</span>
            </el-form-item>
          </el-form>
        </el-card>

        <!-- 右：术语表 -->
        <el-card class="panel">
          <div slot="header" class="card-head">
            <span>术语表（{{ terms.length }}）</span>
            <span>
              <el-button size="mini" icon="el-icon-plus" @click="addRow">新增</el-button>
              <el-button size="mini" type="primary" :loading="savingTerms" @click="saveTerms">保存</el-button>
            </span>
          </div>
          <el-table :data="terms" size="mini" stripe max-height="320">
            <el-table-column label="原文" width="130">
              <template slot-scope="{ row }"><el-input v-model="row.sourceTerm" size="mini" placeholder="原文" /></template>
            </el-table-column>
            <el-table-column label="译法" width="130">
              <template slot-scope="{ row }"><el-input v-model="row.targetTerm" size="mini" placeholder="指定译法" /></template>
            </el-table-column>
            <el-table-column label="类别" width="120">
              <template slot-scope="{ row }">
                <el-select v-model="row.category" size="mini" placeholder="类别" filterable allow-create>
                  <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="说明">
              <template slot-scope="{ row }"><el-input v-model="row.note" size="mini" placeholder="依据/备注" /></template>
            </el-table-column>
            <el-table-column label="来源" width="60">
              <template slot-scope="{ row }">
                <el-tag size="mini" :type="row.origin==='auto' ? 'warning' : 'info'">
                  {{ row.origin==='auto' ? '自动' : '人工' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="50">
              <template slot-scope="{ row }">
                <el-switch v-model="row.enabled" :active-value="1" :inactive-value="0" />
              </template>
            </el-table-column>
            <el-table-column label="" width="44">
              <template slot-scope="{ row, $index }">
                <el-button type="text" class="del" @click="removeTerm(row, $index)">删</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="tip">「自动」为 Gemini 联网抽取，待你审核；改完点保存。启用的术语会在翻译时强制套用。</div>
        </el-card>
      </div>

      <!-- 任务列表 -->
      <el-card v-if="projectId" class="panel">
        <div slot="header">本项目任务</div>
        <el-table :data="projectTasks" size="small" stripe>
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column prop="originalFilename" label="文件" show-overflow-tooltip />
          <el-table-column label="状态" width="200">
            <template slot-scope="{ row }">
              <el-tag size="mini" :type="tagType(row.status)">{{ statusText(row.status) }}</el-tag>
              <el-progress v-if="!isFinal(row.status)" :percentage="row.progress || 0" :stroke-width="6" />
              <span v-if="row.status === 'FAILED'" class="errmsg">{{ row.errorMsg }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="360">
            <template slot-scope="{ row }">
              <el-button v-if="row.status === 'DONE'" type="text" @click="download(row.id)">下载SRT</el-button>
              <el-button v-if="row.status === 'DONE' || row.status === 'BURNING'" type="text"
                         @click="openStyle(row.id)">字幕样式/烧录</el-button>
              <el-button v-if="row.hasVideo" type="text" @click="downloadVideo(row.id)">下载视频</el-button>
              <el-button v-if="row.status === 'DONE' || row.status === 'FAILED'" type="text" @click="retry(row)">重试</el-button>
              <el-button type="text" class="del" @click="removeTask(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-empty v-if="!projectId" description="先选择或新建一个系列项目" />
    </div>

    <style-dialog :visible.sync="styleVisible" :task-id="styleTaskId" @burned="fetchTasks" />

    <!-- 新建项目弹窗 -->
    <el-dialog title="新建系列项目" :visible.sync="newProjVisible" width="420px">
      <el-form label-width="80px">
        <el-form-item label="项目名"><el-input v-model="newProj.name" placeholder="如：本多频道" /></el-form-item>
        <el-form-item label="源语言">
          <el-select v-model="newProj.sourceLang" style="width:100%">
            <el-option label="自动检测" value="auto" />
            <el-option label="日语" value="日语" />
            <el-option label="英语" value="英语" />
            <el-option label="韩语" value="韩语" />
            <el-option label="中文" value="中文" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标语言">
          <el-select v-model="newProj.targetLang" style="width:100%">
            <el-option label="中文" value="中文" />
            <el-option label="英语" value="英语" />
            <el-option label="日语" value="日语" />
            <el-option label="韩语" value="韩语" />
          </el-select>
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="newProjVisible = false">取消</el-button>
        <el-button type="primary" :loading="creatingProj" @click="createProject">创建</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import http from '../api/http'
import StyleDialog from '../components/StyleDialog.vue'

const STATUS_TEXT = {
  PENDING: '排队中', EXTRACTING_AUDIO: '提取音频', TRANSCRIBING: '语音转文字',
  ANALYZING_VIDEO: '分析视频', BUILDING_KB: '抽取术语', TRANSLATING: 'AI 翻译中',
  BURNING: '烧录字幕', DONE: '完成', FAILED: '失败'
}

// 术语六大类
const CATEGORIES = ['人名', '地名与地址', '机构组织与品牌', '作品名', '文化专有项', '科技专名']

export default {
  name: 'KbMode',
  components: { StyleDialog },
  data () {
    return {
      categories: CATEGORIES,
      projects: [],
      projectId: null,
      terms: [],
      tasks: [],
      timer: null,
      rawFile: null,
      fileList: [],
      asrProvider: 'groq',
      bilingual: false,
      submitting: false,
      savingTerms: false,
      newProjVisible: false,
      creatingProj: false,
      styleVisible: false,
      styleTaskId: null,
      newProj: { name: '', sourceLang: 'auto', targetLang: '中文' }
    }
  },
  computed: {
    projectTasks () {
      return this.tasks.filter(t => t.projectId === this.projectId)
    }
  },
  mounted () {
    this.loadProjects()
    this.fetchTasks()
    this.timer = setInterval(this.fetchTasks, 2000)
  },
  beforeDestroy () {
    clearInterval(this.timer)
  },
  methods: {
    async loadProjects () {
      try {
        const r = await http.get('/kb/projects')
        this.projects = r.data || []
        if (!this.projectId && this.projects.length) {
          this.projectId = this.projects[0].id
          this.loadTerms()
        }
      } catch (e) { /* ignore */ }
    },
    onProjectChange () { this.loadTerms() },
    async loadTerms () {
      if (!this.projectId) return
      try {
        const r = await http.get(`/kb/projects/${this.projectId}/terms`)
        this.terms = r.data || []
      } catch (e) { /* ignore */ }
    },
    addRow () {
      this.terms.unshift({ id: null, sourceTerm: '', targetTerm: '', category: '', note: '', origin: 'manual', enabled: 1 })
    },
    async saveTerms () {
      this.savingTerms = true
      try {
        await http.put(`/kb/projects/${this.projectId}/terms`, { terms: this.terms })
        this.$message.success('术语表已保存')
        this.loadTerms()
        this.loadProjects()
      } catch (e) { /* ignore */ } finally { this.savingTerms = false }
    },
    async removeTerm (row, idx) {
      if (!row.id) { this.terms.splice(idx, 1); return }
      try {
        await http.delete(`/kb/terms/${row.id}`)
        this.terms.splice(idx, 1)
        this.$message.success('已删除')
      } catch (e) { /* ignore */ }
    },
    onFileChange (file, fileList) { this.rawFile = file.raw; this.fileList = fileList.slice(-1) },
    onRemove () { this.rawFile = null; this.fileList = [] },
    async submit () {
      if (!this.rawFile) return this.$message.warning('请先选择视频')
      const proj = this.projects.find(p => p.id === this.projectId)
      this.submitting = true
      try {
        const fd = new FormData()
        fd.append('file', this.rawFile)
        fd.append('mode', 'KB')
        fd.append('projectId', this.projectId)
        fd.append('sourceLang', proj ? proj.sourceLang : 'auto')
        fd.append('targetLang', proj ? proj.targetLang : '中文')
        fd.append('asrProvider', this.asrProvider)
        fd.append('bilingual', this.bilingual)
        await http.post('/tasks', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
        this.$message.success('任务已提交')
        this.rawFile = null
        this.fileList = []
        this.fetchTasks()
      } catch (e) { /* ignore */ } finally { this.submitting = false }
    },
    fetchTasks () {
      http.get('/tasks').then(r => {
        this.tasks = r.data
        // 任务完成后术语表可能新增了自动术语，刷新一下
        if (this.projectId) this.maybeRefreshTerms()
      }).catch(() => {})
    },
    maybeRefreshTerms () {
      const building = this.projectTasks.some(t => t.status === 'BUILDING_KB')
      if (building) this._sawBuilding = true
      else if (this._sawBuilding) { this._sawBuilding = false; this.loadTerms(); this.loadProjects() }
    },
    async download (id) {
      try {
        const resp = await http.get(`/tasks/${id}/srt`, { responseType: 'blob' })
        const url = URL.createObjectURL(resp.data)
        const a = document.createElement('a')
        a.href = url; a.download = `subtitle-${id}.srt`; a.click()
        URL.revokeObjectURL(url)
      } catch (e) { /* ignore */ }
    },
    async downloadVideo (id) {
      try {
        const resp = await http.get(`/tasks/${id}/video`, { responseType: 'blob' })
        const url = URL.createObjectURL(resp.data)
        const a = document.createElement('a')
        a.href = url; a.download = `burned-${id}.mp4`; a.click()
        URL.revokeObjectURL(url)
      } catch (e) { /* ignore */ }
    },
    openStyle (id) {
      this.styleTaskId = id
      this.styleVisible = true
    },
    retry (row) {
      this.$confirm(`重跑任务「${row.originalFilename || row.id}」？将清除现有字幕、复用已上传视频重新处理。`, '重试', {
        confirmButtonText: '重试', cancelButtonText: '取消', type: 'warning'
      }).then(async () => {
        try { await http.post(`/tasks/${row.id}/retry`); this.$message.success('已重新提交'); this.fetchTasks() } catch (e) { /* */ }
      }).catch(() => {})
    },
    removeTask (row) {
      this.$confirm(`删除任务「${row.originalFilename || row.id}」？`, '提示', {
        confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
      }).then(async () => {
        try { await http.delete(`/tasks/${row.id}`); this.$message.success('已删除'); this.fetchTasks() } catch (e) { /* */ }
      }).catch(() => {})
    },
    async createProject () {
      if (!this.newProj.name) return this.$message.warning('请填项目名')
      this.creatingProj = true
      try {
        const r = await http.post('/kb/projects', this.newProj)
        this.$message.success('已创建')
        this.newProjVisible = false
        this.newProj = { name: '', sourceLang: 'auto', targetLang: '中文' }
        await this.loadProjects()
        this.projectId = r.data.projectId
        this.loadTerms()
      } catch (e) { /* ignore */ } finally { this.creatingProj = false }
    },
    removeProject () {
      this.$confirm('删除该系列项目及其全部术语？任务记录不受影响。', '提示', {
        confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
      }).then(async () => {
        try {
          await http.delete(`/kb/projects/${this.projectId}`)
          this.$message.success('已删除')
          this.projectId = null
          this.terms = []
          this.loadProjects()
        } catch (e) { /* ignore */ }
      }).catch(() => {})
    },
    statusText (s) { return STATUS_TEXT[s] || s },
    isFinal (s) { return s === 'DONE' || s === 'FAILED' },
    tagType (s) { return s === 'DONE' ? 'success' : (s === 'FAILED' ? 'danger' : 'info') }
  }
}
</script>

<style scoped>
.topbar {
  height: 56px; background: #5b6ee1; color: #fff;
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 24px; font-size: 18px;
}
.body { max-width: 1100px; margin: 20px auto; padding: 0 16px; display: flex; flex-direction: column; gap: 16px; }
.proj-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.proj-bar .lbl { font-weight: 600; }
.grid { display: grid; grid-template-columns: 360px 1fr; gap: 16px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.hint { color: #999; font-size: 12px; }
.tip { color: #999; font-size: 12px; margin-top: 8px; }
.errmsg { color: #f56c6c; font-size: 12px; }
.del { color: #f56c6c; }
</style>
