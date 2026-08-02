<template>
  <el-dialog title="欢迎使用 — 首次配置向导" :visible.sync="visibleProxy" width="580px"
             :close-on-click-modal="false" append-to-body>
    <el-steps :active="step" finish-status="success" simple style="margin-bottom:18px">
      <el-step title="语音识别" icon="el-icon-microphone" />
      <el-step title="AI 翻译" icon="el-icon-connection" />
      <el-step title="完成" icon="el-icon-success" />
    </el-steps>

    <div v-if="step === 0" class="step-body">
      <p>第一步：配置<b>语音识别</b>（把声音变成文字）。推荐 Groq，注册即送免费额度：</p>
      <ol>
        <li>打开 <a href="https://console.groq.com/keys" target="_blank" rel="noopener">console.groq.com/keys</a>（部分网络需要代理才能访问）</li>
        <li>注册 / 登录后点 <b>Create API Key</b>，复制生成的 key（gsk_ 开头）</li>
        <li>粘贴到下面：</li>
      </ol>
      <el-input v-model="groqApiKey" placeholder="gsk_ 开头的 Groq API Key" show-password clearable />
      <p class="tip">暂时拿不到？可以先跳过——识别引擎选「本地 Whisper」可离线识别（需装可选组件，见教程）。</p>
    </div>

    <div v-if="step === 1" class="step-body">
      <p>第二步：配置<b>AI 翻译</b>用的大模型。任何 OpenAI 兼容接口都可以，推荐 DeepSeek（国内直连、价格低）：</p>
      <ol>
        <li>打开 <a href="https://platform.deepseek.com" target="_blank" rel="noopener">platform.deepseek.com</a>，注册并充值少量金额</li>
        <li>在「API Keys」页创建 key 并复制（sk- 开头）</li>
      </ol>
      <el-form label-width="90px" size="small" style="margin-top:8px">
        <el-form-item label="接口地址">
          <el-input v-model="llmBaseUrl" placeholder="https://api.deepseek.com" clearable />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="llmApiKey" placeholder="sk-..." show-password clearable />
        </el-form-item>
        <el-form-item label="模型名称">
          <el-input v-model="llmModel" placeholder="deepseek-chat" clearable />
        </el-form-item>
      </el-form>
    </div>

    <div v-if="step === 2" class="step-body done">
      <i class="el-icon-success big" />
      <p style="font-size:15px">配置完成！回到首页上传一个视频或音频试试吧。</p>
      <p class="tip">密钥随时可在「设置 → API 密钥」里修改；更多说明见
        <a @click.prevent="goGuide" href="#">使用教程</a>。</p>
    </div>

    <div slot="footer" class="footer">
      <el-checkbox v-if="step < 2" v-model="dismiss">不再提示</el-checkbox>
      <span v-else />
      <span>
        <el-button v-if="step < 2" type="text" @click="skip">跳过</el-button>
        <el-button v-if="step === 1" @click="step = 0">上一步</el-button>
        <el-button v-if="step === 0" type="primary" @click="step = 1">下一步</el-button>
        <el-button v-if="step === 1" type="primary" :loading="saving" @click="save">保存并完成</el-button>
        <el-button v-if="step === 2" type="primary" @click="close">开始使用</el-button>
      </span>
    </div>
  </el-dialog>
</template>

<script>
import http from '../api/http'

const DISMISS_KEY = 'aifanyi.setupDismissed'

export default {
  name: 'SetupWizard',
  props: { visible: Boolean },
  data () {
    return {
      step: 0,
      groqApiKey: '',
      llmBaseUrl: 'https://api.deepseek.com',
      llmApiKey: '',
      llmModel: 'deepseek-chat',
      dismiss: false,
      saving: false
    }
  },
  computed: {
    visibleProxy: {
      get () { return this.visible },
      set (v) { this.$emit('update:visible', v) }
    }
  },
  methods: {
    /** 只提交填了的字段，留空的不覆盖已有设置 */
    async save () {
      this.saving = true
      try {
        const body = {}
        if (this.groqApiKey.trim()) body.groqApiKey = this.groqApiKey.trim()
        if (this.llmApiKey.trim()) {
          body.llmApiKey = this.llmApiKey.trim()
          body.llmBaseUrl = this.llmBaseUrl.trim() || 'https://api.deepseek.com'
          body.llmModel = this.llmModel.trim() || 'deepseek-chat'
        }
        if (Object.keys(body).length) {
          await http.put('/settings', body)
          this.$message.success('密钥已保存')
        }
        this.step = 2
      } catch (e) { /* 拦截器已提示 */ } finally { this.saving = false }
    },
    skip () {
      if (this.dismiss) localStorage.setItem(DISMISS_KEY, '1')
      this.visibleProxy = false
    },
    close () {
      localStorage.setItem(DISMISS_KEY, '1')
      this.visibleProxy = false
    },
    goGuide () {
      this.close()
      this.$router.push('/guide')
    }
  }
}
</script>

<style scoped>
.step-body { min-height: 190px; font-size: 14px; color: #333; }
.step-body ol { padding-left: 20px; margin: 8px 0; line-height: 1.9; }
.step-body a { color: #5b6ee1; }
.tip { color: #999; font-size: 12px; margin-top: 10px; }
.done { text-align: center; padding-top: 30px; }
.big { font-size: 56px; color: #67c23a; }
.footer { display: flex; justify-content: space-between; align-items: center; }
</style>
