<template>
  <div class="create-page">
    <div class="page-head">
      <h2>音频/视频翻译</h2>
      <p>上传视频或音频，AI 自动识别语音并翻译，产出字幕与译文</p>
    </div>

    <el-card class="form-card">
      <el-form label-width="90px">
        <el-form-item label="媒体文件">
          <el-upload action="#" :auto-upload="false" multiple :on-change="onFileChange"
                     :on-remove="onRemove" :file-list="fileList" drag :accept="accept">
            <i class="el-icon-upload" />
            <div class="el-upload__text">拖拽文件到这里，或 <em>点击选择</em></div>
            <div slot="tip" class="el-upload__tip">
              视频：MP4 / MKV / MOV / AVI / WebM 等；音频：MP3 / WAV / M4A / AAC / FLAC / OGG 等。
              <b>可一次选多个文件</b>，将按同一套设置依次排队处理
            </div>
          </el-upload>
        </el-form-item>

        <el-form-item label="已有字幕">
          <el-switch v-model="useSubtitle" :disabled="files.length > 1" />
          <span class="hint" style="margin-left:10px">
            <template v-if="files.length > 1">批量提交时不支持自带字幕（一份字幕对不上多个文件）</template>
            <template v-else>手头已有原文 SRT/VTT？开启后跳过语音识别，直接翻译，快十倍以上</template>
          </span>
          <div v-if="useSubtitle && files.length <= 1" style="margin-top:8px">
            <el-upload action="#" :auto-upload="false" :limit="1" :on-change="onSubChange"
                       :on-remove="onSubRemove" :file-list="subList" accept=".srt,.vtt">
              <el-button size="small" icon="el-icon-document">选择字幕文件（.srt / .vtt）</el-button>
            </el-upload>
            <div class="hint">字幕的时间轴会原样保留，系统不做纠轴与反幻觉处理（那是给机器识别结果纠错用的）</div>
          </div>
        </el-form-item>

        <el-form-item label="目标语言">
          <el-select v-model="targetLang" filterable style="width:100%">
            <el-option-group v-for="g in langGroups" :key="g.label" :label="g.label">
              <el-option v-for="l in g.langs" :key="l" :label="l" :value="l" />
            </el-option-group>
          </el-select>
        </el-form-item>

        <el-form-item v-if="!skipAsr" label="语音识别">
          <el-select v-model="asrProvider" style="width:100%">
            <el-option label="Groq（需要使用魔法，使用large-v3模型进行翻译）" value="groq">
              <span>Groq（需要使用魔法，使用large-v3模型进行翻译）</span>
              <span class="opt-eta">{{ etaText('groq') }}</span>
            </el-option>
            <el-option label="Groq Turbo（需要使用魔法）" value="groq-turbo">
              <span>Groq Turbo（需要使用魔法）</span>
              <span class="opt-eta">{{ etaText('groq-turbo') }}</span>
            </el-option>
            <el-option label="Qwen3-ASR（敬请期待）" value="qwen" disabled />
            <el-option label="GLM-ASR（尽情期待）" value="glm" disabled />
            <el-option-group label="本地 Whisper（GPU/CPU）">
              <el-option v-for="o in localOptions" :key="o.value" :label="o.label" :value="o.value">
                <span>{{ o.label }}</span>
                <span class="opt-eta">{{ etaText(o.value) }}</span>
              </el-option>
            </el-option-group>
          </el-select>
          <div v-if="etaHint" class="eta-hint">
            <i class="el-icon-time" /> {{ etaHint }}
          </div>
        </el-form-item>
        <el-form-item v-else label="语音识别">
          <span class="hint">已自带字幕，本次跳过语音识别</span>
        </el-form-item>

        <el-form-item label="选项">
          <el-checkbox v-model="bilingual">双语字幕</el-checkbox>
        </el-form-item>
        <el-form-item label="术语库">
          <GlossaryPicker v-model="glossaryIds" />
          <div class="hint">选中术语库中的术语将注入本次翻译，保证译法统一（可多选）</div>
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
          <el-button type="primary" :loading="submitting" icon="el-icon-position" @click="submit">
            {{ files.length > 1 ? `开始翻译（${files.length} 个文件）` : '开始翻译' }}
          </el-button>
          <span class="hint" style="margin-left:12px">
            <template v-if="submitting && files.length > 1">正在提交第 {{ submitted + 1 }} / {{ files.length }} 个…</template>
            <template v-else>提交后可到「翻译历史」查看进度</template>
          </span>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script>
import http from '../api/http'
import { LANG_GROUPS } from '../constants/langs'
import stylePresetsMixin from '../mixins/stylePresets'
import GlossaryPicker from '../components/GlossaryPicker.vue'

// 2026-08 需求改版：音频翻译与视频翻译合并为「音频/视频翻译」，同一入口收两类文件
const MEDIA_ACCEPT = 'video/*,audio/*,.mp4,.mkv,.mov,.avi,.webm,.flv,.ts,.m4v,.wmv,.mp3,.wav,.m4a,.aac,.flac,.ogg,.opus,.wma'

export default {
  name: 'NormalMode',
  components: { GlossaryPicker },
  mixins: [stylePresetsMixin],
  data () {
    return {
      files: [],
      fileList: [],
      useSubtitle: false,
      subFile: null,
      subList: [],
      targetLang: '中文',
      langGroups: LANG_GROUPS,
      asrProvider: 'groq',
      bilingual: false,
      glossaryIds: [0],
      submitting: false,
      submitted: 0,
      // 各识别档位的实测倍速（媒体时长 ÷ 转写耗时），由后端按本机 GPU/CPU 给出
      speeds: {},
      // 本地读到的媒体总时长（秒），用来把倍速换算成「预计等多久」
      mediaSec: 0
    }
  },
  computed: {
    accept () { return MEDIA_ACCEPT },
    skipAsr () { return this.useSubtitle && !!this.subFile },
    localOptions () {
      return [
        { value: 'local-base', label: '本地 base' },
        { value: 'local-small', label: '本地 small' },
        { value: 'local-medium', label: '本地 medium' },
        { value: 'local-large-v3', label: '本地 large-v3' }
      ]
    },
    etaHint () {
      if (!this.mediaSec) return ''
      const eta = this.etaSec(this.asrProvider)
      if (!eta) return ''
      const total = this.files.length || 1
      const one = `预计识别耗时 ${this.fmt(eta)}（媒体时长 ${this.fmt(this.mediaSec)}）`
      return total > 1 ? `${one}，${total} 个文件依次处理约 ${this.fmt(eta * total)}` : one
    }
  },
  async created () {
    try {
      const { data } = await http.get('/settings/asr/speed')
      const map = {};
      (data || []).forEach(o => { map[o.value] = o.speedFactor })
      this.speeds = map
    } catch (e) { /* 拿不到就不显示预估，不影响提交 */ }
  },
  methods: {
    onFileChange (file, fileList) {
      this.fileList = fileList
      this.files = fileList.map(f => f.raw).filter(Boolean)
      if (this.files.length > 1) {
        this.useSubtitle = false
        this.subFile = null
        this.subList = []
      }
      this.probeDuration(this.files[0])
    },
    onRemove (file, fileList) {
      this.fileList = fileList
      this.files = fileList.map(f => f.raw).filter(Boolean)
      this.probeDuration(this.files[0])
    },
    onSubChange (file, fileList) {
      this.subFile = file.raw
      this.subList = fileList.slice(-1)
    },
    onSubRemove () {
      this.subFile = null
      this.subList = []
    },
    /** 用浏览器解码媒体元数据拿总时长——不上传、不解码画面，纯本地几毫秒。 */
    probeDuration (raw) {
      this.mediaSec = 0
      if (!raw) return
      const url = URL.createObjectURL(raw)
      const el = document.createElement(raw.type.startsWith('audio') ? 'audio' : 'video')
      el.preload = 'metadata'
      el.onloadedmetadata = () => {
        this.mediaSec = Number.isFinite(el.duration) ? el.duration : 0
        URL.revokeObjectURL(url)
      }
      el.onerror = () => { URL.revokeObjectURL(url) }
      el.src = url
    },
    etaSec (provider) {
      const factor = this.speeds[provider]
      if (!factor || !this.mediaSec) return 0
      return this.mediaSec / factor
    },
    etaText (provider) {
      const eta = this.etaSec(provider)
      return eta ? `≈${this.fmt(eta)}` : ''
    },
    fmt (sec) {
      if (sec < 60) return `${Math.max(1, Math.round(sec))} 秒`
      const m = Math.floor(sec / 60)
      const s = Math.round(sec % 60)
      if (m < 60) return s ? `${m} 分 ${s} 秒` : `${m} 分钟`
      return `${Math.floor(m / 60)} 小时 ${m % 60} 分`
    },
    buildForm (raw) {
      const fd = new FormData()
      fd.append('file', raw)
      fd.append('mode', 'NORMAL')
      fd.append('targetLang', this.targetLang)
      fd.append('asrProvider', this.asrProvider)
      fd.append('bilingual', this.bilingual)
      fd.append('stylePrompt', this.styleEnabled ? this.stylePrompt.trim() : '')
      const gids = this.glossaryIds.filter(x => x !== 0)
      if (gids.length) fd.append('glossaryProjectIds', gids.join(','))
      if (this.skipAsr) fd.append('subtitleFile', this.subFile)
      return fd
    },
    async submit () {
      if (!this.files.length) return this.$message.warning('请先选择视频或音频文件')
      if (this.useSubtitle && !this.subFile) return this.$message.warning('已开启「已有字幕」，请选择字幕文件或关掉该开关')
      this.submitting = true
      this.submitted = 0
      let ok = 0
      const failed = []
      try {
        // 逐个提交而不是并发：后端 taskExecutor 本来就排队，
        // 一个个发能让失败精确到文件名，也不会把大文件的上传带宽互相挤掉
        for (const raw of this.files) {
          try {
            await http.post('/tasks', this.buildForm(raw),
              { headers: { 'Content-Type': 'multipart/form-data' } })
            ok++
          } catch (e) {
            failed.push(raw.name)
          }
          this.submitted++
        }
        if (ok) {
          this.$message.success(failed.length
            ? `已提交 ${ok} 个任务，${failed.length} 个失败：${failed.join('、')}`
            : (ok > 1 ? `已提交 ${ok} 个任务，将依次处理` : '任务已提交，正在跳转到翻译历史…'))
          this.files = []
          this.fileList = []
          this.subFile = null
          this.subList = []
          this.useSubtitle = false
          this.mediaSec = 0
          this.$router.push('/tasks')
        } else {
          this.$message.error(`全部 ${failed.length} 个文件提交失败`)
        }
      } finally {
        this.submitting = false
      }
    }
  }
}
</script>

<style scoped>
.create-page { max-width: 760px; margin: 0 auto; }
.form-card { padding: 6px 8px; }
.hint { color: #a0aec0; font-size: 12px; }
.opt-eta { float: right; color: #909399; font-size: 12px; margin-left: 20px; }
.eta-hint { color: #67809f; font-size: 12px; margin-top: 6px; }
</style>
