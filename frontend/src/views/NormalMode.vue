<template>
  <div class="create-page">
    <div class="page-head">
      <h2>{{ isAudio ? '音频AI翻译' : '普通AI视频翻译' }}</h2>
      <p>{{ isAudio ? '上传音频，AI 识别并翻译，产出 SRT 字幕与译文 TXT'
                    : '上传视频，AI 自动识别语音并翻译' }}</p>
    </div>

    <el-card class="form-card">
      <el-form label-width="90px">
        <el-form-item :label="isAudio ? '音频文件' : '视频文件'">
          <el-upload action="#" :auto-upload="false" :limit="1" :on-change="onFileChange"
                     :on-remove="onRemove" :file-list="fileList" drag :accept="accept">
            <i class="el-icon-upload" />
            <div class="el-upload__text">拖拽文件到这里，或 <em>点击选择</em></div>
            <div slot="tip" class="el-upload__tip">
              {{ isAudio ? '支持 MP3 / WAV / M4A / AAC / FLAC / OGG 等' : '支持 MP4 / MKV / MOV / AVI / WebM 等（也可直接上传音频）' }}
            </div>
          </el-upload>
        </el-form-item>
        <el-form-item label="目标语言">
          <el-select v-model="targetLang" filterable style="width:100%">
            <el-option-group v-for="g in langGroups" :key="g.label" :label="g.label">
              <el-option v-for="l in g.langs" :key="l" :label="l" :value="l" />
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item label="语音识别">
          <el-select v-model="asrProvider" style="width:100%">
            <el-option label="Groq（需要使用魔法，使用large-v3模型进行翻译）" value="groq" />
            <el-option label="Groq Turbo（需要使用魔法）" value="groq-turbo" />
            <el-option label="Qwen3-ASR（敬请期待）" value="qwen" disabled />
            <el-option label="GLM-ASR（尽情期待）" value="glm" disabled />
            <el-option-group label="本地 Whisper（GPU/CPU）">
              <el-option label="本地 base" value="local-base" />
              <el-option label="本地 small" value="local-small" />
              <el-option label="本地 medium" value="local-medium" />
              <el-option label="本地 large-v3" value="local-large-v3" />
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item label="选项">
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
                      placeholder="描述期望的翻译风格，如：古风文雅、网络流行语、正式书面…" />
          </template>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" icon="el-icon-position" @click="submit">开始翻译</el-button>
          <span class="hint" style="margin-left:12px">提交后可到「我的任务」查看进度</span>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script>
import http from '../api/http'
import { LANG_GROUPS } from '../constants/langs'
import stylePresetsMixin from '../mixins/stylePresets'

const AUDIO_ACCEPT = 'audio/*,.mp3,.wav,.m4a,.aac,.flac,.ogg,.opus,.wma'
const VIDEO_ACCEPT = 'video/*,audio/*,.mp4,.mkv,.mov,.avi,.webm,.flv,.ts,.m4v,.wmv,.mp3,.wav,.m4a,.aac,.flac,.ogg,.opus,.wma'

export default {
  name: 'NormalMode',
  mixins: [stylePresetsMixin],
  data () {
    return {
      rawFile: null,
      fileList: [],
      targetLang: '中文',
      langGroups: LANG_GROUPS,
      asrProvider: 'groq',
      bilingual: false,
      submitting: false
    }
  },
  computed: {
    isAudio () { return this.$route.meta.kind === 'audio' },
    accept () { return this.isAudio ? AUDIO_ACCEPT : VIDEO_ACCEPT }
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
      if (!this.rawFile) return this.$message.warning(this.isAudio ? '请先选择音频文件' : '请先选择视频或音频文件')
      this.submitting = true
      try {
        const fd = new FormData()
        fd.append('file', this.rawFile)
        fd.append('mode', 'NORMAL')
        fd.append('targetLang', this.targetLang)
        fd.append('asrProvider', this.asrProvider)
        fd.append('bilingual', this.bilingual)
        fd.append('stylePrompt', this.styleEnabled ? this.stylePrompt.trim() : '')
        await http.post('/tasks', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
        this.$message.success('任务已提交，正在跳转到我的任务…')
        this.rawFile = null
        this.fileList = []
        this.$router.push('/tasks')
      } catch (e) { /* ignore */ } finally { this.submitting = false }
    }
  }
}
</script>

<style scoped>
.create-page { max-width: 760px; margin: 0 auto; }
.form-card { padding: 6px 8px; }
</style>
