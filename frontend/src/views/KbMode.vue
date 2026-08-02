<template>
  <div class="page">
    <div class="page-head">
      <h2>术语库AI视频翻译</h2>
      <p>一系列视频共用一张术语表，ai自动提取术语并进行联网查验</p>
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
                <el-option label="Groq（需要使用魔法，使用large-v3模型进行翻译）" value="groq" />
                <el-option label="Groq Turbo（需要使用魔法）" value="groq-turbo" />
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
            <el-form-item label="翻译风格">
              <el-switch v-model="styleEnabled" />
              <template v-if="styleEnabled">
                <div class="style-presets">
                  <el-tag v-for="p in stylePresets" :key="p.label" size="small" effect="plain"
                          class="style-tag" @click="stylePrompt = p.prompt">{{ p.label }}</el-tag>
                </div>
                <el-input v-model="stylePrompt" type="textarea" :rows="2" maxlength="500" show-word-limit
                          placeholder="描述期望的翻译风格，如：古风文雅、网络流行语…（术语表优先于风格）" />
              </template>
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
          <el-table-column label="操作" width="430">
            <template slot-scope="{ row }">
              <el-button v-if="row.status === 'DONE'" type="text" icon="el-icon-edit-outline"
                         @click="$router.push(`/editor/${row.id}`)">编辑字幕</el-button>
              <el-button v-if="row.status === 'DONE'" type="text" @click="download(row.id)">下载SRT</el-button>
              <el-button v-if="row.mediaType !== 'AUDIO' && (row.status === 'DONE' || row.status === 'BURNING')"
                         type="text" @click="openStyle(row.id)">烧录</el-button>
              <el-button v-if="row.hasVideo" type="text" @click="downloadVideo(row.id)">下载视频</el-button>
              <el-button v-if="isFinal(row.status)" type="text" @click="retry(row)">重试</el-button>
              <el-button type="text" class="del" @click="remove(row)">删除</el-button>
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
        <el-form-item label="项目名"><el-input v-model="newProj.name" placeholder="如：奥特曼系列" /></el-form-item>
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
import stylePresetsMixin from '../mixins/stylePresets'
import taskOps from '../mixins/taskOps'
import { statusText, isFinal, tagType } from '../constants/status'
import { blobDown } from '../utils/download'
import { TERM_CATEGORIES } from '../constants/kb'

export default {
  name: 'KbMode',
  components: { StyleDialog },
  mixins: [stylePresetsMixin, taskOps],
  data () {
    return {
      categories: TERM_CATEGORIES,
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
    statusText, isFinal, tagType,
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
        fd.append('stylePrompt', this.styleEnabled ? this.stylePrompt.trim() : '')
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
    download (id) { blobDown(`/tasks/${id}/srt`, `subtitle-${id}.srt`) },
    downloadVideo (id) { blobDown(`/tasks/${id}/video`, `burned-${id}.mp4`) },
    openStyle (id) {
      this.styleTaskId = id
      this.styleVisible = true
    },
    retry (row) { this.retryTask(row, this.fetchTasks) },
    remove (row) { this.removeTask(row, this.fetchTasks) },
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
    }
  }
}
</script>

<style scoped>
.body { max-width: 1100px; margin: 0 auto; display: flex; flex-direction: column; gap: 16px; }
.proj-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.proj-bar .lbl { font-weight: 600; }
.grid { display: grid; grid-template-columns: 360px 1fr; gap: 16px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.tip { color: #999; font-size: 12px; margin-top: 8px; }
.errmsg { color: #f56c6c; font-size: 12px; }
</style>
