<template>
  <div class="page">
    <div class="topbar">
      <el-button type="text" style="color:#fff" icon="el-icon-back" @click="$router.push('/')">返回</el-button>
      <span>普通 AI 视频翻译</span>
      <span />
    </div>

    <div class="body">
      <el-card class="panel">
        <div slot="header">新建翻译任务</div>
        <el-form label-width="90px">
          <el-form-item label="视频文件">
            <el-upload action="#" :auto-upload="false" :limit="1" :on-change="onFileChange"
                       :on-remove="onRemove" :file-list="fileList" accept="video/*">
              <el-button icon="el-icon-upload2">选择视频</el-button>
            </el-upload>
          </el-form-item>
          <el-form-item label="目标语言">
            <el-select v-model="targetLang">
              <el-option label="中文" value="中文" />
              <el-option label="英语" value="英语" />
              <el-option label="日语" value="日语" />
              <el-option label="韩语" value="韩语" />
            </el-select>
          </el-form-item>
          <el-form-item label="语音识别">
            <el-select v-model="asrProvider" style="width:100%">
              <el-option label="Groq（推荐使用）" value="groq" />
              <el-option label="Groq Turbo（更快，稍逊）" value="groq-turbo" />
              <el-option label="Qwen3-ASR（待接入，需阿里 Key）" value="qwen" disabled />
              <el-option label="GLM-ASR（待接入，需智谱余额）" value="glm" disabled />
              <el-option-group label="本地 Whisper（需 GPU 部署）">
                <el-option label="本地 base（最快/质量较低）" value="local-base" disabled />
                <el-option label="本地 small" value="local-small" disabled />
                <el-option label="本地 medium" value="local-medium" disabled />
                <el-option label="本地 large-v3（最准）" value="local-large-v3" disabled />
              </el-option-group>
            </el-select>
          </el-form-item>
          <el-form-item label="选项">
            <el-checkbox v-model="bilingual">双语字幕</el-checkbox>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="submitting" @click="submit">开始翻译</el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card class="panel">
        <div slot="header">我的任务</div>
        <el-table :data="tasks" size="small" stripe>
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column prop="originalFilename" label="文件" show-overflow-tooltip />
          <el-table-column prop="targetLang" label="目标语言" width="90" />
          <el-table-column label="状态" width="200">
            <template slot-scope="{ row }">
              <el-tag size="mini" :type="tagType(row.status)">{{ statusText(row.status) }}</el-tag>
              <el-progress v-if="!isFinal(row.status)" :percentage="row.progress || 0" :stroke-width="6" />
              <span v-if="row.status === 'FAILED'" class="err">{{ row.errorMsg }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220">
            <template slot-scope="{ row }">
              <el-button v-if="row.status === 'DONE'" type="text" @click="download(row.id)">下载SRT</el-button>
              <el-button v-if="row.status === 'DONE' || row.status === 'BURNING'" type="text"
                         @click="openStyle(row.id)">字幕样式/烧录</el-button>
              <el-button v-if="row.hasVideo" type="text" @click="downloadVideo(row.id)">下载视频</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <style-dialog :visible.sync="styleVisible" :task-id="styleTaskId" @burned="fetchTasks" />
  </div>
</template>

<script>
import http from '../api/http'
import StyleDialog from '../components/StyleDialog.vue'

const STATUS_TEXT = {
  PENDING: '排队中', EXTRACTING_AUDIO: '提取音频', TRANSCRIBING: '语音转文字',
  ANALYZING_VIDEO: '分析视频', BUILDING_KB: '构建知识库', TRANSLATING: 'AI 翻译中',
  BURNING: '烧录字幕', DONE: '完成', FAILED: '失败'
}

export default {
  name: 'NormalMode',
  components: { StyleDialog },
  data () {
    return {
      rawFile: null,
      fileList: [],
      targetLang: '中文',
      asrProvider: 'groq',
      bilingual: false,
      submitting: false,
      tasks: [],
      timer: null,
      styleVisible: false,
      styleTaskId: null
    }
  },
  mounted () {
    this.fetchTasks()
    this.timer = setInterval(this.fetchTasks, 2000)
  },
  beforeDestroy () {
    clearInterval(this.timer)
  },
  methods: {
    onFileChange (file, fileList) {
      this.rawFile = file.raw
      this.fileList = fileList.slice(-1)
    },
    onRemove () {
      this.rawFile = null
      this.fileList = []
    },
    async submit () {
      if (!this.rawFile) return this.$message.warning('请先选择视频')
      this.submitting = true
      try {
        const fd = new FormData()
        fd.append('file', this.rawFile)
        fd.append('mode', 'NORMAL')
        fd.append('targetLang', this.targetLang)
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
      http.get('/tasks').then(r => { this.tasks = r.data }).catch(() => {})
    },
    async download (id) {
      try {
        const resp = await http.get(`/tasks/${id}/srt`, { responseType: 'blob' })
        const url = URL.createObjectURL(resp.data)
        const a = document.createElement('a')
        a.href = url
        a.download = `subtitle-${id}.srt`
        a.click()
        URL.revokeObjectURL(url)
      } catch (e) { /* ignore */ }
    },
    async downloadVideo (id) {
      try {
        const resp = await http.get(`/tasks/${id}/video`, { responseType: 'blob' })
        const url = URL.createObjectURL(resp.data)
        const a = document.createElement('a')
        a.href = url
        a.download = `burned-${id}.mp4`
        a.click()
        URL.revokeObjectURL(url)
      } catch (e) { /* ignore */ }
    },
    openStyle (id) {
      this.styleTaskId = id
      this.styleVisible = true
    },
    statusText (s) { return STATUS_TEXT[s] || s },
    isFinal (s) { return s === 'DONE' || s === 'FAILED' },
    tagType (s) {
      if (s === 'DONE') return 'success'
      if (s === 'FAILED') return 'danger'
      return 'info'
    }
  }
}
</script>

<style scoped>
.topbar {
  height: 56px; background: #5b6ee1; color: #fff;
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 24px; font-size: 18px;
}
.body { max-width: 900px; margin: 24px auto; display: flex; flex-direction: column; gap: 20px; }
.err { color: #f56c6c; font-size: 12px; }
</style>
