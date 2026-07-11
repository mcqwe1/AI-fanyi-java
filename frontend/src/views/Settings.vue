<template>
  <div class="page">
    <div class="topbar">
      <el-button type="text" style="color:#fff" icon="el-icon-back" @click="$router.push('/')">返回</el-button>
      <span>设置</span>
      <span />
    </div>

    <div class="body">
      <el-tabs v-model="tab">
        <!-- API 密钥 -->
        <el-tab-pane label="API 密钥" name="keys">
          <el-alert type="info" :closable="false" show-icon style="margin-bottom:16px"
                    title="所有密钥/Base URL/模型均在此配置并仅存于你自己的数据库（不再读环境变量）；留空表示不修改，保存后对新任务生效。" />
          <el-form label-width="150px" style="max-width:560px">
            <el-divider content-position="left">翻译模型（OpenAI 兼容）</el-divider>
            <el-form-item label="Base URL">
              <el-input v-model="form.llmBaseUrl" placeholder="如 https://api.deepseek.com/v1" />
            </el-form-item>
            <el-form-item label="API Key">
              <el-input v-model="form.llmApiKey" :placeholder="ph('llmApiKey')" show-password />
            </el-form-item>
            <el-form-item label="模型">
              <div style="display:flex; gap:8px">
                <el-select v-model="form.llmModel" filterable allow-create default-first-option
                           placeholder="选择或输入模型名，如 deepseek-v4-flash" style="flex:1">
                  <el-option v-for="m in llmModels" :key="m" :label="m" :value="m" />
                </el-select>
                <el-button :loading="fetchingModels" @click="fetchModels">拉取模型</el-button>
              </div>
              <div class="tip">用上方 Base URL + API Key 拉取该端点支持的模型（Key 留空则用已保存的）；
                走本机 Gemini 代理时建议选 -nothinking 后缀变体（关闭内置思考，翻译提速约 5 倍）</div>
            </el-form-item>

            <el-divider content-position="left">翻译风格（默认）</el-divider>
            <el-form-item label="风格提示词">
              <div class="style-presets">
                <el-tag v-for="p in stylePresets" :key="p.label" size="small" effect="plain"
                        class="style-tag" @click="form.stylePrompt = p.prompt">{{ p.label }}</el-tag>
              </div>
              <el-input v-model="form.stylePrompt" type="textarea" :rows="2" maxlength="500" show-word-limit
                        placeholder="如：古风文雅，用词考究…（留空=不设默认风格）" />
              <div class="tip">保存后作为三个翻译模式的默认风格，任务里可临时修改或关闭；清空并保存即取消默认。</div>
            </el-form-item>

            <el-divider content-position="left">语音识别</el-divider>
            <el-form-item label="Groq API Key">
              <el-input v-model="form.groqApiKey" :placeholder="ph('groqApiKey')" show-password />
            </el-form-item>
            <el-form-item label="阿里 DashScope Key">
              <el-input v-model="form.dashscopeApiKey" :placeholder="ph('dashscopeApiKey')" show-password />
              <div class="tip">Qwen3-ASR 用，需 sk- 开头的 DashScope API Key</div>
            </el-form-item>
            <el-form-item label="智谱 Key">
              <el-input v-model="form.zhipuApiKey" :placeholder="ph('zhipuApiKey')" show-password />
              <div class="tip">GLM-ASR 用，格式 id.secret</div>
            </el-form-item>

            <el-divider content-position="left">知识库 Gemini（联网抽术语）</el-divider>
            <el-form-item label="Base URL">
              <el-input v-model="form.geminiBaseUrl"
                        placeholder="本机代理 http://localhost:8050/v1 或官方 https://generativelanguage.googleapis.com/v1beta/openai" />
            </el-form-item>
            <el-form-item label="Gemini API Key">
              <el-input v-model="form.geminiApiKey" :placeholder="ph('geminiApiKey')" show-password />
            </el-form-item>
            <el-form-item label="模型">
              <div style="display:flex; gap:8px">
                <el-select v-model="form.geminiModel" filterable allow-create default-first-option
                           placeholder="选择或输入模型名，如 gemini-3-flash-preview-search" style="flex:1">
                  <el-option v-for="m in geminiModels" :key="m" :label="m" :value="m" />
                </el-select>
                <el-button :loading="fetchingGeminiModels" @click="fetchGeminiModels">拉取模型</el-button>
              </div>
              <div class="tip">-search 后缀模型＝联网搜索核实译法（仅抽术语用；KB 翻译时后端自动改用
                -nothinking 变体关闭思考提速）；Key 留空则用已保存的</div>
            </el-form-item>

            <el-form-item>
              <el-button type="primary" :loading="savingKeys" @click="saveKeys">保存密钥</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- 修改密码 -->
        <el-tab-pane label="修改密码" name="pwd">
          <el-form label-width="100px" style="max-width:420px">
            <el-form-item label="原密码">
              <el-input v-model="pwd.oldPassword" type="password" show-password />
            </el-form-item>
            <el-form-item label="新密码">
              <el-input v-model="pwd.newPassword" type="password" show-password placeholder="至少 6 位" />
            </el-form-item>
            <el-form-item label="确认新密码">
              <el-input v-model="pwd.confirm" type="password" show-password @keyup.enter.native="changePwd" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="savingPwd" @click="changePwd">修改密码</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- 历史记录 -->
        <el-tab-pane label="历史记录" name="history">
          <el-table :data="tasks" size="small" stripe>
            <el-table-column prop="id" label="ID" width="60" />
            <el-table-column prop="originalFilename" label="文件" show-overflow-tooltip />
            <el-table-column prop="mode" label="模式" width="80" />
            <el-table-column prop="targetLang" label="目标语言" width="90" />
            <el-table-column label="状态" width="90">
              <template slot-scope="{ row }">
                <el-tag size="mini" :type="row.status==='DONE'?'success':(row.status==='FAILED'?'danger':'info')">
                  {{ row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="170" />
            <el-table-column label="操作" width="70">
              <template slot-scope="{ row }">
                <el-button type="text" class="del" @click="remove(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script>
import http from '../api/http'
import { STYLE_PRESETS } from '../constants/styles'

export default {
  name: 'Settings',
  data () {
    return {
      tab: 'keys',
      settings: null,
      form: {
        groqApiKey: '', llmBaseUrl: '', llmApiKey: '', llmModel: '',
        dashscopeApiKey: '', zhipuApiKey: '',
        geminiBaseUrl: '', geminiApiKey: '', geminiModel: '',
        stylePrompt: ''
      },
      stylePresets: STYLE_PRESETS,
      pwd: { oldPassword: '', newPassword: '', confirm: '' },
      tasks: [],
      llmModels: [],
      fetchingModels: false,
      geminiModels: [],
      fetchingGeminiModels: false,
      savingKeys: false,
      savingPwd: false
    }
  },
  mounted () {
    this.loadSettings()
    this.loadHistory()
  },
  methods: {
    async loadSettings () {
      try {
        const r = await http.get('/settings')
        this.settings = r.data
        this.form.llmBaseUrl = r.data.llmBaseUrl || ''
        this.form.llmModel = r.data.llmModel || ''
        this.form.geminiBaseUrl = r.data.geminiBaseUrl || ''
        this.form.geminiModel = r.data.geminiModel || ''
        this.form.stylePrompt = r.data.stylePrompt || ''
      } catch (e) { /* ignore */ }
    },
    loadHistory () {
      http.get('/tasks').then(r => { this.tasks = r.data }).catch(() => {})
    },
    remove (row) {
      this.$confirm(`确定删除任务「${row.originalFilename || row.id}」吗？字幕与文件将一并清除，不可恢复。`, '删除任务', {
        confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
      }).then(async () => {
        try {
          await http.delete(`/tasks/${row.id}`)
          this.$message.success('已删除')
          this.loadHistory()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    ph (field) {
      const s = this.settings && this.settings[field]
      return s && s.set ? `已配置 ${s.masked}，留空不改` : '未配置'
    },
    async fetchModels () {
      this.fetchingModels = true
      try {
        const r = await http.post('/settings/llm/models', {
          baseUrl: this.form.llmBaseUrl,
          apiKey: this.form.llmApiKey
        })
        this.llmModels = r.data || []
        this.$message.success(`拉取到 ${this.llmModels.length} 个模型`)
      } catch (e) { /* 拦截器已提示 */ } finally { this.fetchingModels = false }
    },
    async fetchGeminiModels () {
      this.fetchingGeminiModels = true
      try {
        const r = await http.post('/settings/gemini/models', {
          baseUrl: this.form.geminiBaseUrl,
          apiKey: this.form.geminiApiKey
        })
        this.geminiModels = r.data || []
        this.$message.success(`拉取到 ${this.geminiModels.length} 个模型`)
      } catch (e) { /* 拦截器已提示 */ } finally { this.fetchingGeminiModels = false }
    },
    async saveKeys () {
      this.savingKeys = true
      try {
        await http.put('/settings', this.form)
        this.$message.success('已保存')
        // 清空密钥输入，重新拉取打码值
        this.form.groqApiKey = this.form.llmApiKey = this.form.dashscopeApiKey = ''
        this.form.zhipuApiKey = this.form.geminiApiKey = ''
        this.loadSettings()
      } catch (e) { /* ignore */ } finally { this.savingKeys = false }
    },
    async changePwd () {
      if (!this.pwd.oldPassword || !this.pwd.newPassword) return this.$message.warning('请填写密码')
      if (this.pwd.newPassword.length < 6) return this.$message.warning('新密码至少 6 位')
      if (this.pwd.newPassword !== this.pwd.confirm) return this.$message.warning('两次新密码不一致')
      this.savingPwd = true
      try {
        await http.post('/auth/change-password', {
          oldPassword: this.pwd.oldPassword, newPassword: this.pwd.newPassword
        })
        this.$message.success('密码已修改')
        this.pwd = { oldPassword: '', newPassword: '', confirm: '' }
      } catch (e) { /* ignore */ } finally { this.savingPwd = false }
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
.body { max-width: 900px; margin: 24px auto; padding: 0 16px; }
.tip { color: #999; font-size: 12px; line-height: 1.6; }
.style-presets { margin: 6px 0; }
.style-tag { cursor: pointer; margin-right: 6px; }
.del { color: #f56c6c; }
</style>
