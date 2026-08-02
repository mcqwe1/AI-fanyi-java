<template>
  <div class="page">
    <div class="page-head">
      <h2>设置</h2>
      <p>在这里配置ai模型，tts，语音转文字模型。</p>
    </div>

    <div class="body">
      <el-tabs v-model="tab">
        <!-- API 密钥 -->
        <el-tab-pane label="API 密钥" name="keys">
          <el-alert type="info" :closable="false" show-icon style="margin-bottom:16px"
                    title="所有密钥/Base URL/模型均在此配置并仅存于你自己的本地当中；留空表示不修改，保存后对新任务生效。(一定要保存)" />
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
                建议所有模型都不开启思考功能，避免翻译时间过长。</div>
            </el-form-item>

            <el-divider content-position="left">语音识别(云服务)</el-divider>
            <el-form-item label="Groq API Key">
              <el-input v-model="form.groqApiKey" :placeholder="ph('groqApiKey')" show-password />
              <div class="tip">需要开启魔法</div>
            </el-form-item>
            <el-form-item label="阿里 DashScope Key">
              <el-input v-model="form.dashscopeApiKey" :placeholder="ph('dashscopeApiKey')" show-password />
              <div class="tip">敬请期待</div>
            </el-form-item>
            <el-form-item label="智谱 Key">
              <el-input v-model="form.zhipuApiKey" :placeholder="ph('zhipuApiKey')" show-password />
              <div class="tip">敬请期待</div>
            </el-form-item>

            <el-divider content-position="left">语音合成 TTS（配音）</el-divider>
            <el-form-item label="配音引擎">
              <div class="engine-list">
                <div v-for="e in ttsEngines" :key="e.id" class="engine-card"
                     :class="{ active: form.ttsProvider === e.id }" @click="form.ttsProvider = e.id">
                  <div class="engine-head">
                    <span class="engine-name">{{ e.name }}</span>
                    <el-tag size="mini" :type="e.tag === 'free' ? 'success' : (e.tag === 'cloud' ? '' : 'info')">
                      {{ e.tag === 'free' ? '免费' : (e.tag === 'cloud' ? '需Key' : '自定义') }}
                    </el-tag>
                    <el-tag v-if="e.configured" size="mini" type="success" effect="plain">已就绪</el-tag>
                  </div>
                  <div class="engine-desc">{{ e.desc }}</div>
                </div>
              </div>
            </el-form-item>

            <template v-if="form.ttsProvider === 'siliconflow'">
              <el-form-item label="API Key">
                <el-input v-model="form.ttsApiKey" :placeholder="ph('ttsApiKey')" show-password />
                <div class="tip">到 siliconflow.cn 注册 → API 密钥页新建（sk- 开头）</div>
              </el-form-item>
            </template>
            <template v-else-if="form.ttsProvider === 'openai'">
              <el-form-item label="Base URL">
                <el-input v-model="form.ttsBaseUrl" placeholder="OpenAI 兼容端点，如 https://api.openai.com/v1" />
              </el-form-item>
              <el-form-item label="API Key">
                <el-input v-model="form.ttsApiKey" :placeholder="ph('ttsApiKey')" show-password />
              </el-form-item>
              <el-form-item label="模型">
                <div style="display:flex; gap:8px">
                  <el-select v-model="form.ttsModel" filterable allow-create default-first-option
                             placeholder="选择或输入模型名，如 tts-1" style="flex:1">
                    <el-option v-for="m in ttsModels" :key="m" :label="m" :value="m" />
                  </el-select>
                  <el-button :loading="fetchingTtsModels" @click="fetchTtsModels">拉取模型</el-button>
                </div>
                <div class="tip">调用 {Base URL}/audio/speech 合成配音</div>
              </el-form-item>
            </template>
            <el-form-item v-else-if="form.ttsProvider === 'edge'">
              <div class="tip">免费开箱即用，无需任何配置（合成时需联网）</div>
            </el-form-item>

            <el-divider content-position="left">gemini模型配置(此处配置的模型仅用于术语库的提取术语功能)</el-divider>
            <el-form-item label="Base URL">
              <el-input v-model="form.geminiBaseUrl"
                        placeholder="请输入Base URL...." />
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
              <div class="tip">请务必使用带有search后缀的模型</div>
            </el-form-item>

            <el-form-item>
              <el-button type="primary" :loading="savingKeys" @click="saveKeys">保存密钥</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- 风格与术语 -->
        <el-tab-pane label="风格与术语" name="style">
          <el-form label-width="150px" style="max-width:640px">
            <el-divider content-position="left">翻译风格（默认）</el-divider>
            <el-form-item label="默认风格">
              <div class="style-presets">
                <el-tag v-for="p in allPresets" :key="p.label" size="small" effect="plain"
                        class="style-tag" @click="form.stylePrompt = p.prompt">{{ p.label }}</el-tag>
              </div>
              <el-input v-model="form.stylePrompt" type="textarea" :rows="2" maxlength="500" show-word-limit
                        placeholder="如：古风文雅，用词考究…（留空=不设默认风格）" />
              <div class="tip">保存后作为三个翻译模式的默认风格，任务里可临时修改或关闭；清空并保存即取消默认。
                想长期保存多个自定义风格，见下方「我的翻译风格预设」。</div>
              <div style="margin-top:8px">
                <el-button type="primary" size="small" :loading="savingStyle" @click="saveStylePrompt">保存默认风格</el-button>
              </div>
            </el-form-item>

            <el-divider content-position="left">我的翻译风格预设</el-divider>
            <el-form-item label="自定义风格">
              <div class="tip" style="margin-bottom:8px">
                这里保存的风格会出现在所有翻译模式的风格下拉里，随时选用。内置风格（{{ builtinNames }}）无需在此添加。
              </div>
              <el-table :data="presets" size="small" stripe empty-text="还没有自定义风格，点下方新增">
                <el-table-column label="名称" width="140">
                  <template slot-scope="{ row }"><el-input v-model="row.label" size="mini" maxlength="60" placeholder="如：影视字幕腔" /></template>
                </el-table-column>
                <el-table-column label="风格提示词">
                  <template slot-scope="{ row }"><el-input v-model="row.prompt" size="mini" type="textarea" :autosize="{minRows:1,maxRows:4}" maxlength="500" placeholder="描述期望的语气/用词/句式…" /></template>
                </el-table-column>
                <el-table-column width="90">
                  <template slot-scope="{ row, $index }">
                    <el-button type="text" :loading="row._saving" @click="savePreset(row)">保存</el-button>
                    <el-button type="text" class="del" @click="deletePreset(row, $index)">删</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-button size="small" icon="el-icon-plus" style="margin-top:10px" @click="addPreset">新增风格</el-button>
            </el-form-item>

            <el-divider content-position="left">术语抽取提示词（知识库模式）</el-divider>
            <el-form-item label="自定义提示词">
              <el-switch v-model="termCustomOn" active-text="使用自定义" inactive-text="用系统内置"
                         @change="onTermToggle" />
              <div class="tip" style="margin:8px 0">
                开启后，知识库模式抽取术语时改用你的提示词，完全覆盖系统内置。
                支持占位符 <code>{sourceLang}</code>／<code>{targetLang}</code>（源/目标语言自动替换），
                可选 <code>{transcript}</code>（转写全文，不写也会自动附在后面）。
                模型需按 <code>{"terms":[{"source","target","category","note"}]}</code> 返回 JSON。
              </div>
              <el-input v-if="termCustomOn" v-model="form.termExtractPrompt" type="textarea"
                        :autosize="{minRows:6,maxRows:20}" placeholder="在此粘贴或编写你的术语抽取提示词…" />
              <div v-if="termCustomOn" style="margin-top:8px">
                <el-button size="mini" @click="fillDefaultTermPrompt">填入系统内置作为起点</el-button>
                <el-button size="mini" type="primary" :loading="savingTerm" @click="saveTermPrompt">保存提示词</el-button>
              </div>
              <el-collapse v-else style="margin-top:6px">
                <el-collapse-item title="查看当前系统内置提示词（点击展开）">
                  <pre class="prompt-view">{{ settings && settings.defaultTermPrompt }}</pre>
                </el-collapse-item>
              </el-collapse>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- 全能AI翻译（Agent 模式） -->
        <el-tab-pane label="全能AI翻译" name="agent">
          <el-form label-width="150px" style="max-width:640px">
            <div class="tip" style="margin-bottom:12px">
              这一页只影响「全能AI翻译」模式，<b>全部可以留空</b>——留空就沿用上面「API 密钥」里已配好的翻译模型。
            </div>

            <el-divider content-position="left">读稿与仲裁的模型</el-divider>
            <div class="tip" style="margin-bottom:8px">
              负责判断内容属于哪个领域、以及最后汇总各专家的意见。要求理解力好，调用次数少。
            </div>
            <el-form-item label="Base URL">
              <el-input v-model="form.agentMainBaseUrl" placeholder="留空=用「API 密钥」里的翻译模型" />
            </el-form-item>
            <el-form-item label="API Key">
              <el-input v-model="form.agentMainApiKey" :placeholder="ph('agentMainApiKey')" show-password />
            </el-form-item>
            <el-form-item label="模型">
              <div style="display:flex; gap:8px">
                <el-select v-model="form.agentMainModel" filterable allow-create default-first-option
                           placeholder="选择或输入模型名，如 deepseek-chat" style="flex:1">
                  <el-option v-for="m in agentMainModels" :key="m" :label="m" :value="m" />
                </el-select>
                <el-button :loading="fetchingAgentMain" @click="fetchAgentMainModels">拉取模型</el-button>
              </div>
            </el-form-item>

            <el-divider content-position="left">领域专家的模型</el-divider>
            <div class="tip" style="margin-bottom:8px">
              负责抽取术语、核实译法。会被并行调用多次（每个领域一个专家），建议选便宜快速的模型。
            </div>
            <el-form-item label="Base URL">
              <el-input v-model="form.agentSubBaseUrl" placeholder="留空=用「API 密钥」里的翻译模型" />
            </el-form-item>
            <el-form-item label="API Key">
              <el-input v-model="form.agentSubApiKey" :placeholder="ph('agentSubApiKey')" show-password />
            </el-form-item>
            <el-form-item label="模型">
              <div style="display:flex; gap:8px">
                <el-select v-model="form.agentSubModel" filterable allow-create default-first-option
                           placeholder="选择或输入模型名，如 deepseek-chat" style="flex:1">
                  <el-option v-for="m in agentSubModels" :key="m" :label="m" :value="m" />
                </el-select>
                <el-button :loading="fetchingAgentSub" @click="fetchAgentSubModels">拉取模型</el-button>
              </div>
            </el-form-item>

            <el-divider content-position="left">联网核实</el-divider>
            <div class="tip" style="margin-bottom:8px">
              遇到拿不准的专有名词时上网查一下通行译法。<b>不配也能用</b>，只是生僻专名的准确率会下降。
            </div>
            <el-form-item label="搜索引擎">
              <div class="engine-list">
                <div v-for="e in searchEngines" :key="e.id" class="engine-card"
                     :class="{ active: form.searchProvider === e.id }" @click="form.searchProvider = e.id">
                  <div class="engine-head">
                    <span class="engine-name">{{ e.name }}</span>
                    <el-tag size="mini" :type="e.tag === 'free' ? 'success' : (e.tag === 'cloud' ? '' : 'info')">
                      {{ e.tag === 'free' ? '免费' : (e.tag === 'cloud' ? '需Key' : '自定义') }}
                    </el-tag>
                    <el-tag v-if="e.configured && e.needKey" size="mini" type="success" effect="plain">已就绪</el-tag>
                  </div>
                  <div class="engine-desc">{{ e.desc }}</div>
                </div>
              </div>
            </el-form-item>
            <template v-if="currentSearchEngine && currentSearchEngine.needKey">
              <el-form-item label="搜索 API Key">
                <el-input v-model="form.searchApiKey" :placeholder="ph('searchApiKey')" show-password />
              </el-form-item>
              <el-form-item label="搜索 Base URL">
                <el-input v-model="form.searchBaseUrl"
                          :placeholder="currentSearchEngine.defaultBaseUrl || '留空=用默认端点'" />
              </el-form-item>
            </template>

            <el-divider content-position="left">LangSmith 云端调试（开发者功能）</el-divider>
            <div class="tip" style="margin-bottom:8px">
              把每次模型调用的<b>完整提示词和返回</b>上报到 LangSmith 网页端，方便逐步排查。
              <b>注意：内容含视频完整台词，会发送到 LangSmith 的服务器</b>——不调试就别填，留空即完全关闭。
            </div>
            <el-form-item label="LangSmith Key">
              <el-input v-model="form.langsmithApiKey" :placeholder="ph('langsmithApiKey')" show-password />
            </el-form-item>
            <el-form-item label="项目名">
              <div style="display:flex; gap:8px">
                <el-input v-model="form.langsmithProject" placeholder="留空=aifanyi" style="flex:1" />
                <el-button :loading="testingLs" @click="testLangsmith">测试连接</el-button>
              </div>
              <div v-if="lsTestResult" class="tip">{{ lsTestResult }}</div>
            </el-form-item>

            <el-form-item>
              <el-button type="primary" :loading="savingAgent" @click="saveAgent">保存</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- 账户 -->
        <el-tab-pane label="账户" name="pwd">
          <el-form label-width="100px" style="max-width:420px">
            <el-divider content-position="left">修改用户名</el-divider>
            <el-form-item label="当前用户名">
              <el-input :value="$store.state.username" disabled />
            </el-form-item>
            <el-form-item label="新用户名">
              <el-input v-model="uname.newUsername" maxlength="32" placeholder="2~32 个字符" />
            </el-form-item>
            <el-form-item label="登录密码">
              <el-input v-model="uname.password" type="password" show-password placeholder="验证身份" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="savingUname" @click="changeUsername">修改用户名</el-button>
            </el-form-item>

            <el-divider content-position="left">修改密码</el-divider>
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
                <el-tag size="mini" :type="tagType(row.status)">
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
import { tagType } from '../constants/status'
import taskOps from '../mixins/taskOps'

export default {
  name: 'Settings',
  mixins: [taskOps],
  data () {
    return {
      tab: this.$route.query.tab || 'keys',
      settings: null,
      form: {
        groqApiKey: '', llmBaseUrl: '', llmApiKey: '', llmModel: '',
        dashscopeApiKey: '', zhipuApiKey: '',
        geminiBaseUrl: '', geminiApiKey: '', geminiModel: '',
        ttsProvider: '', ttsBaseUrl: '', ttsApiKey: '', ttsModel: '',
        agentMainBaseUrl: '', agentMainApiKey: '', agentMainModel: '',
        agentSubBaseUrl: '', agentSubApiKey: '', agentSubModel: '',
        searchProvider: '', searchBaseUrl: '', searchApiKey: '',
        langsmithApiKey: '', langsmithProject: '',
        stylePrompt: '', termExtractPrompt: ''
      },
      ttsEngines: [],
      searchEngines: [],
      savingAgent: false,
      testingLs: false,
      lsTestResult: '',
      agentMainModels: [],
      fetchingAgentMain: false,
      agentSubModels: [],
      fetchingAgentSub: false,
      presets: [],            // 用户自定义风格（后端）
      termCustomOn: false,
      savingTerm: false,
      savingStyle: false,
      pwd: { oldPassword: '', newPassword: '', confirm: '' },
      uname: { newUsername: '', password: '' },
      savingUname: false,
      tasks: [],
      llmModels: [],
      fetchingModels: false,
      geminiModels: [],
      fetchingGeminiModels: false,
      ttsModels: [],
      fetchingTtsModels: false,
      savingKeys: false,
      savingPwd: false
    }
  },
  computed: {
    builtinNames () { return STYLE_PRESETS.map(p => p.label).join('、') },
    // 内置 + 自定义,给「默认风格」快捷标签用
    allPresets () { return [...STYLE_PRESETS, ...this.presets.filter(p => p.label && p.prompt)] },
    currentSearchEngine () {
      return this.searchEngines.find(e => e.id === this.form.searchProvider) || null
    }
  },
  mounted () {
    this.loadSettings()
    this.loadPresets()
    this.loadHistory()
    this.loadTtsEngines()
    this.loadSearchEngines()
  },
  methods: {
    tagType,
    loadTtsEngines () {
      http.get('/settings/tts/engines').then(r => { this.ttsEngines = r.data || [] }).catch(() => {})
    },
    loadSearchEngines () {
      http.get('/settings/search/engines').then(r => { this.searchEngines = r.data || [] }).catch(() => {})
    },
    async saveAgent () {
      this.savingAgent = true
      try {
        // 只提交本页字段，其余不传即不动（后端按字段判空写入）
        await http.put('/settings', {
          agentMainBaseUrl: this.form.agentMainBaseUrl,
          agentMainApiKey: this.form.agentMainApiKey,
          agentMainModel: this.form.agentMainModel,
          agentSubBaseUrl: this.form.agentSubBaseUrl,
          agentSubApiKey: this.form.agentSubApiKey,
          agentSubModel: this.form.agentSubModel,
          // 传空串是合法的：表示显式选择「不联网」
          searchProvider: this.form.searchProvider || '',
          searchBaseUrl: this.form.searchBaseUrl,
          searchApiKey: this.form.searchApiKey,
          langsmithApiKey: this.form.langsmithApiKey,
          langsmithProject: this.form.langsmithProject
        })
        this.$message.success('已保存')
        this.form.agentMainApiKey = this.form.agentSubApiKey = this.form.searchApiKey = ''
        this.form.langsmithApiKey = ''
        this.loadSettings()
        this.loadSearchEngines()
      } catch (e) { /* ignore */ } finally { this.savingAgent = false }
    },
    async testLangsmith () {
      this.testingLs = true
      this.lsTestResult = ''
      try {
        // 表单填了用表单的（未保存也能测）；留空走已保存的。baseUrl 字段位借放项目名。
        const r = await http.post('/settings/langsmith/test', {
          apiKey: this.form.langsmithApiKey,
          baseUrl: this.form.langsmithProject
        })
        this.lsTestResult = r.data || ''
      } catch (e) { /* ignore */ } finally { this.testingLs = false }
    },
    async loadSettings () {
      try {
        const r = await http.get('/settings')
        this.settings = r.data
        this.form.llmBaseUrl = r.data.llmBaseUrl || ''
        this.form.llmModel = r.data.llmModel || ''
        this.form.geminiBaseUrl = r.data.geminiBaseUrl || ''
        this.form.geminiModel = r.data.geminiModel || ''
        this.form.ttsProvider = r.data.ttsProvider || ''
        this.form.ttsBaseUrl = r.data.ttsBaseUrl || ''
        this.form.ttsModel = r.data.ttsModel || ''
        this.form.agentMainBaseUrl = r.data.agentMainBaseUrl || ''
        this.form.agentMainModel = r.data.agentMainModel || ''
        this.form.agentSubBaseUrl = r.data.agentSubBaseUrl || ''
        this.form.agentSubModel = r.data.agentSubModel || ''
        this.form.searchProvider = r.data.searchProvider || ''
        this.form.searchBaseUrl = r.data.searchBaseUrl || ''
        this.form.stylePrompt = r.data.stylePrompt || ''
        this.form.termExtractPrompt = r.data.termExtractPrompt || ''
        this.termCustomOn = !!(r.data.termExtractPrompt && r.data.termExtractPrompt.trim())
      } catch (e) { /* ignore */ }
    },
    async loadPresets () {
      try {
        const r = await http.get('/style-presets')
        this.presets = (r.data || []).map(p => ({ ...p, _saving: false }))
      } catch (e) { /* ignore */ }
    },
    addPreset () {
      this.presets.push({ id: null, label: '', prompt: '', _saving: false })
    },
    async savePreset (row) {
      if (!row.label || !row.label.trim()) return this.$message.warning('请填风格名称')
      if (!row.prompt || !row.prompt.trim()) return this.$message.warning('请填风格提示词')
      row._saving = true
      try {
        if (row.id) {
          await http.put(`/style-presets/${row.id}`, { label: row.label, prompt: row.prompt })
        } else {
          const r = await http.post('/style-presets', { label: row.label, prompt: row.prompt })
          row.id = r.data.id
        }
        this.$message.success('已保存')
      } catch (e) { /* 拦截器已提示 */ } finally { row._saving = false }
    },
    async deletePreset (row, idx) {
      if (!row.id) { this.presets.splice(idx, 1); return }
      try {
        await http.delete(`/style-presets/${row.id}`)
        this.presets.splice(idx, 1)
        this.$message.success('已删除')
      } catch (e) { /* ignore */ }
    },
    onTermToggle (on) {
      // 关掉自定义 = 立即清空并保存(回退内置);开启只是显示编辑框,需点保存才生效
      if (!on) this.saveTermPrompt(true)
    },
    fillDefaultTermPrompt () {
      if (this.settings && this.settings.defaultTermPrompt) {
        this.form.termExtractPrompt = this.settings.defaultTermPrompt
      }
    },
    async saveTermPrompt (clearing) {
      const val = clearing ? '' : (this.form.termExtractPrompt || '').trim()
      if (!clearing && !val) return this.$message.warning('提示词不能为空（如需关闭请用开关切回内置）')
      this.savingTerm = true
      try {
        // 只提交 termExtractPrompt 字段,不动密钥
        await http.put('/settings', { termExtractPrompt: val })
        this.$message.success(clearing ? '已切回系统内置提示词' : '术语提示词已保存')
        this.loadSettings()
      } catch (e) { /* ignore */ } finally { this.savingTerm = false }
    },
    async saveStylePrompt () {
      this.savingStyle = true
      try {
        // 只提交默认风格字段,不动密钥
        await http.put('/settings', { stylePrompt: (this.form.stylePrompt || '').trim() })
        this.$message.success('默认风格已保存')
        this.loadSettings()
      } catch (e) { /* ignore */ } finally { this.savingStyle = false }
    },
    loadHistory () {
      http.get('/tasks').then(r => { this.tasks = r.data }).catch(() => {})
    },
    remove (row) { this.removeTask(row, this.loadHistory) },
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
    async fetchAgentMainModels () {
      this.fetchingAgentMain = true
      try {
        // 留空时后端按运行时同一条回退链取端点（已存 Agent 配置 → 翻译模型配置）
        const r = await http.post('/settings/agent-main/models', {
          baseUrl: this.form.agentMainBaseUrl,
          apiKey: this.form.agentMainApiKey
        })
        this.agentMainModels = r.data || []
        this.$message.success(`拉取到 ${this.agentMainModels.length} 个模型`)
      } catch (e) { /* 拦截器已提示 */ } finally { this.fetchingAgentMain = false }
    },
    async fetchAgentSubModels () {
      this.fetchingAgentSub = true
      try {
        const r = await http.post('/settings/agent-sub/models', {
          baseUrl: this.form.agentSubBaseUrl,
          apiKey: this.form.agentSubApiKey
        })
        this.agentSubModels = r.data || []
        this.$message.success(`拉取到 ${this.agentSubModels.length} 个模型`)
      } catch (e) { /* 拦截器已提示 */ } finally { this.fetchingAgentSub = false }
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
    async fetchTtsModels () {
      this.fetchingTtsModels = true
      try {
        const r = await http.post('/settings/tts/models', {
          baseUrl: this.form.ttsBaseUrl,
          apiKey: this.form.ttsApiKey
        })
        this.ttsModels = r.data || []
        this.$message.success(`拉取到 ${this.ttsModels.length} 个模型`)
      } catch (e) { /* 拦截器已提示 */ } finally { this.fetchingTtsModels = false }
    },
    async saveKeys () {
      this.savingKeys = true
      try {
        // 只提交密钥,不含 stylePrompt/termExtractPrompt(那两个在「风格与术语」标签单独保存)
        // 也不含 agent*/search*（在「全能AI翻译」标签单独保存）——尤其 searchProvider：
        // 它传空串是「显式选不联网」的合法值，混进这里保存会把用户选好的引擎清掉
        const {
          termExtractPrompt, stylePrompt,
          agentMainBaseUrl, agentMainApiKey, agentMainModel,
          agentSubBaseUrl, agentSubApiKey, agentSubModel,
          searchProvider, searchBaseUrl, searchApiKey,
          ...keyForm
        } = this.form
        await http.put('/settings', keyForm)
        this.$message.success('已保存')
        // 清空密钥输入，重新拉取打码值
        this.form.groqApiKey = this.form.llmApiKey = this.form.dashscopeApiKey = ''
        this.form.zhipuApiKey = this.form.geminiApiKey = this.form.ttsApiKey = ''
        this.loadSettings()
        this.loadTtsEngines()
      } catch (e) { /* ignore */ } finally { this.savingKeys = false }
    },
    async changeUsername () {
      if (!this.uname.newUsername.trim()) return this.$message.warning('请填写新用户名')
      if (!this.uname.password) return this.$message.warning('请输入登录密码验证身份')
      this.savingUname = true
      try {
        const r = await http.post('/auth/change-username', {
          newUsername: this.uname.newUsername.trim(),
          password: this.uname.password
        })
        // 后端签发了新 token（旧 token 的 subject 是旧用户名），立即替换本地登录态
        this.$store.commit('setAuth', { token: r.data.token, username: r.data.username })
        this.uname = { newUsername: '', password: '' }
        this.$message.success('用户名已修改')
      } catch (e) { /* 拦截器已提示 */ } finally { this.savingUname = false }
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
.body { max-width: 900px; margin: 0 auto; }
.tip { color: #999; font-size: 12px; line-height: 1.6; }
.engine-list { display: flex; flex-direction: column; gap: 8px; }
.engine-card {
  border: 1px solid #e4e7ed; border-radius: 10px; padding: 10px 14px;
  cursor: pointer; transition: border-color .15s, background .15s;
}
.engine-card:hover { border-color: #b9c6ff; }
.engine-card.active { border-color: var(--brand, #4c7dff); background: #f4f7ff; }
.engine-head { display: flex; align-items: center; gap: 8px; }
.engine-name { font-weight: 600; font-size: 14px; color: #1f2d3d; }
.engine-desc { color: #8492a6; font-size: 12px; margin-top: 4px; line-height: 1.6; }
.prompt-view {
  white-space: pre-wrap; word-break: break-word; margin: 0;
  background: #f7f8fa; border-radius: 6px; padding: 12px;
  font-size: 12px; line-height: 1.7; color: #555; max-height: 320px; overflow-y: auto;
}
.tip code { background: #f0f2f5; padding: 1px 5px; border-radius: 3px; font-size: 12px; }
</style>
