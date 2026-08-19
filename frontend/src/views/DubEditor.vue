<template>
  <div class="dub-editor">
    <!-- 顶栏 -->
    <div class="ed-top">
      <span class="ed-left">
        <el-button type="text" icon="el-icon-back" class="back" @click="$router.push('/tasks')">返回</el-button>
        <span class="title">TTS 配音</span>
        <span v-if="task" class="fname">{{ task.originalFilename }}（#{{ task.id }}）</span>
      </span>
      <span class="ed-right">
        <el-radio-group v-if="task && task.hasDub" v-model="viewTrack" size="mini" @change="onTrackChange">
          <el-radio-button label="orig">原视频</el-radio-button>
          <el-radio-button label="dub">配音视频</el-radio-button>
        </el-radio-group>
        <el-button v-if="task && task.hasDub" size="small" icon="el-icon-download"
                   @click="downloadDub">下载配音视频</el-button>
      </span>
    </div>

    <div v-loading="!loaded" class="ed-main">
      <!-- 左：播放器 -->
      <div class="left-pane">
        <div class="video-box">
          <video ref="player" :key="mediaUrl" :src="mediaUrl" controls preload="metadata" />
        </div>

        <!-- 配音进行中：进度条 -->
        <el-card v-if="dubbing" class="prog-card" shadow="never">
          <div class="prog-head"><i class="el-icon-loading" /> 正在配音合成…</div>
          <el-progress :percentage="task ? (task.dubProgress || 0) : 0" :stroke-width="10" />
          <div class="hint">逐行合成语音 → 按字幕时间轴拼接 → 混入视频，行数多时需要几分钟</div>
        </el-card>

        <!-- 配音失败提示（主任务与字幕成果不受影响，改完配置可直接重试） -->
        <el-alert v-if="task && task.dubStatus === 'FAILED' && task.dubError" type="error"
                  :title="'配音失败：' + task.dubError" :closable="false" show-icon class="err-alert">
          <div style="margin-top:4px; font-size:12px">翻译成果未受影响，修正后可直接重新配音。</div>
        </el-alert>

        <!-- 配音成功但个别行译文过长：时间轴仍精准，只是这些行会与下一行短暂重叠 -->
        <el-alert v-if="task && task.dubStatus === 'DONE' && task.dubNotice" type="warning"
                  :title="task.dubNotice" :closable="true" show-icon class="err-alert" />
      </div>

      <!-- 右：音色设置 + 字幕预览 -->
      <div class="right-pane">
        <el-card shadow="never" class="voice-card">
          <div slot="header">配音设置</div>
          <el-form label-width="90px" size="small">
            <el-form-item label="音色">
              <el-select v-model="voice" filterable allow-create default-first-option style="width:100%">
                <el-option v-for="v in voices" :key="v.value" :label="v.label" :value="v.value" />
              </el-select>
              <div class="hint">
                {{ voices.length ? '音色由所选配音引擎提供，也可输入引擎支持的其他音色标识'
                                 : '还没选配音引擎——先去「设置 → API 配置 → 语音合成（TTS）」选一个' }}
              </div>
            </el-form-item>
            <el-form-item label="语速">
              <el-slider v-model="speed" :min="0.5" :max="2" :step="0.05"
                         :marks="{ 0.5: '0.5x', 1: '1x', 2: '2x' }" />
            </el-form-item>
            <el-form-item label="保留原声">
              <el-switch v-model="keepOriginal" />
              <span class="hint" style="margin-left:8px">开=原声压低做背景，关=配音完全替换原声</span>
            </el-form-item>
            <el-form-item>
              <el-button :loading="previewing" icon="el-icon-video-play" @click="preview">试听音色</el-button>
              <el-button type="primary" :loading="dubbing" :disabled="!canDub"
                         icon="el-icon-mic" @click="startDub">
                {{ task && task.hasDub ? '重新配音' : '开始配音' }}
              </el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card shadow="never" class="subs-card">
          <div slot="header">将要配音的译文（{{ subs.length }} 行）</div>
          <div class="subs-list">
            <div v-for="s in subs" :key="s.seq" class="sub-row">
              <span class="time">{{ fmt(s.startMs) }}</span>
              <span class="txt">{{ s.targetText || '（空，跳过）' }}</span>
            </div>
          </div>
          <div class="hint" style="margin-top:8px">
            需要改译文？先去 <el-button type="text" class="inline-link"
              @click="$router.push(`/editor/${taskId}`)">字幕编辑器</el-button> 改好再回来配音。
          </div>
        </el-card>
      </div>
    </div>
  </div>
</template>

<script>
import http from '../api/http'
import { blobDown } from '../utils/download'

export default {
  name: 'DubEditor',
  data () {
    return {
      task: null,
      subs: [],
      voices: [],
      voice: 'alloy',
      speed: 1,
      keepOriginal: false,
      viewTrack: 'orig',
      loaded: false,
      previewing: false,
      timer: null,
      previewAudio: null
    }
  },
  computed: {
    taskId () { return this.$route.params.id },
    dubbing () { return this.task && this.task.dubStatus === 'DUBBING' },
    canDub () {
      return this.task && this.task.status === 'DONE' && this.task.mediaType !== 'AUDIO'
        && this.subs.length > 0 && this.task.dubStatus !== 'DUBBING'
    },
    voiceLabel () {
      const v = this.voices.find(x => x.value === this.voice)
      return v ? v.label : this.voice
    },
    mediaUrl () {
      const token = encodeURIComponent(localStorage.getItem('token') || '')
      const track = this.viewTrack === 'dub' ? '&track=dub' : ''
      return `/api/tasks/${this.taskId}/media?access_token=${token}${track}`
    }
  },
  async mounted () {
    await this.fetchTask()
    this.fetchSubs()
    this.fetchVoices()
    // 任务里存过配音参数则带出
    if (this.task) {
      if (this.task.ttsVoice) this.voice = this.task.ttsVoice
      if (this.task.ttsSpeed) this.speed = parseFloat(this.task.ttsSpeed) || 1
      this.keepOriginal = this.task.ttsKeepOriginal === 1
      if (this.task.hasDub) this.viewTrack = 'dub'
    }
    this.loaded = true
    this.timer = setInterval(this.pollTask, 2000)
  },
  beforeDestroy () {
    clearInterval(this.timer)
    if (this.previewAudio) { this.previewAudio.pause(); this.previewAudio = null }
  },
  methods: {
    async fetchTask () {
      try {
        const r = await http.get(`/tasks/${this.taskId}`)
        this.task = r.data
      } catch (e) { /* 拦截器已提示 */ }
    },
    fetchSubs () {
      http.get(`/tasks/${this.taskId}/subtitles`).then(r => { this.subs = r.data || [] }).catch(() => {})
    },
    fetchVoices () {
      http.get(`/tasks/${this.taskId}/tts/voices`).then(r => {
        this.voices = r.data || []
        // 当前音色不在引擎音色表里时默认选第一个（引擎切换后旧音色对新引擎无效）
        if (this.voices.length && !this.voices.some(v => v.value === this.voice)) {
          this.voice = this.voices[0].value
        }
      }).catch(() => {})
    },
    /** 配音中轮询进度；DUBBING→终态时刷新（拿 hasDub/dubError），成功自动切到配音轨 */
    async pollTask () {
      if (!this.task) return
      const wasDubbing = this.dubbing
      await this.fetchTask()
      if (wasDubbing && !this.dubbing) {
        if (this.task.dubStatus === 'DONE' && this.task.hasDub) {
          this.viewTrack = 'dub'
          this.$message.success('配音完成！已切换到配音视频预览')
        } else if (this.task.dubStatus === 'FAILED') {
          this.$message.error('配音失败，请查看下方错误提示')
        }
      }
    },
    async preview () {
      this.previewing = true
      try {
        const r = await http.post(`/tasks/${this.taskId}/tts/preview`,
          { voice: this.voice, speed: this.speed }, { responseType: 'blob' })
        if (this.previewAudio) this.previewAudio.pause()
        const url = URL.createObjectURL(r.data)
        this.previewAudio = new Audio(url)
        this.previewAudio.onended = () => URL.revokeObjectURL(url)
        this.previewAudio.play()
      } catch (e) { /* 拦截器已提示 */ } finally { this.previewing = false }
    },
    startDub () {
      const redo = this.task && this.task.hasDub
      this.$confirm(
        redo ? '重新配音将覆盖现有配音视频，继续？'
             : `将用「${this.voiceLabel}」音色为 ${this.subs.length} 行译文配音并合成视频，继续？`,
        '开始配音', { confirmButtonText: '开始', cancelButtonText: '取消', type: 'info' }
      ).then(async () => {
        try {
          await http.post(`/tasks/${this.taskId}/tts/dub`,
            { voice: this.voice, speed: this.speed, keepOriginal: this.keepOriginal })
          this.$message.success('配音任务已提交')
          this.viewTrack = 'orig'
          this.fetchTask()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    onTrackChange () {
      // :key 绑定 mediaUrl，切换轨道时 video 元素自动重建加载
    },
    downloadDub () {
      blobDown(`/tasks/${this.taskId}/dub-video`, `dubbed-${this.taskId}.mp4`)
    },
    fmt (ms) {
      const t = Math.max(0, Math.floor(ms / 1000))
      const m = Math.floor(t / 60)
      const s = t % 60
      return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
    }
  }
}
</script>

<style scoped>
.dub-editor { height: 100vh; display: flex; flex-direction: column; background: #f5f7fb; }
.ed-top {
  height: 52px; flex-shrink: 0; background: #fff; border-bottom: 1px solid #eef1f6;
  display: flex; align-items: center; justify-content: space-between; padding: 0 18px;
}
.ed-left { display: flex; align-items: center; gap: 12px; }
.ed-right { display: flex; align-items: center; gap: 12px; }
.title { font-weight: 700; font-size: 15px; }
.fname { color: #8492a6; font-size: 13px; }

.ed-main { flex: 1; display: flex; gap: 16px; padding: 16px; min-height: 0; }
.left-pane { flex: 1.2; display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.right-pane { flex: 1; display: flex; flex-direction: column; gap: 12px; min-width: 380px; overflow-y: auto; }

.video-box { background: #000; border-radius: 10px; overflow: hidden; }
.video-box video { width: 100%; max-height: 56vh; display: block; }

.prog-card .prog-head { font-weight: 600; margin-bottom: 10px; }
.err-alert { border-radius: 8px; }

.subs-card { flex: 1; display: flex; flex-direction: column; min-height: 0; }
.subs-list { max-height: 40vh; overflow-y: auto; }
.sub-row { display: flex; gap: 10px; padding: 4px 0; border-bottom: 1px dashed #eee; font-size: 13px; }
.sub-row .time { color: #8492a6; flex-shrink: 0; font-family: monospace; }
.sub-row .txt { color: #303133; word-break: break-word; }
.inline-link { padding: 0; }
</style>
