<template>
  <div class="page">
    <div class="page-head">
      <h2>全能AI翻译 <el-tag size="mini" type="warning" effect="plain">实验</el-tag></h2>
      <p>多个领域专家同时读稿、各自抽取术语并核实译法，再带着术语表翻译。视频 / 音频 / 文本都能处理。</p>
    </div>

    <div class="body">
      <div class="grid">
        <!-- 左：新建任务 -->
        <el-card class="panel">
          <div slot="header" class="card-head">
            <span>新建翻译任务</span>
            <el-button size="mini" icon="el-icon-user" @click="openExperts">我的专家</el-button>
          </div>
          <el-form label-width="80px">
            <el-form-item label="文件">
              <el-upload action="#" drag :auto-upload="false" :limit="1" :on-change="onFileChange"
                         :on-remove="onRemove" :file-list="fileList"
                         accept="video/*,audio/*,.txt,.md,.markdown,.srt,.vtt">
                <i class="el-icon-upload" />
                <div class="el-upload__text">拖文件到这里，或<em>点击选择</em></div>
                <div slot="tip" class="el-upload__tip">视频、音频、文本都支持（文本仅本模式可用）</div>
              </el-upload>
            </el-form-item>
            <el-form-item v-if="needsAsr" label="语音识别">
              <el-select v-model="asrProvider" style="width:100%">
                <el-option label="Groq（需要使用魔法，large-v3 模型）" value="groq" />
                <el-option label="Groq Turbo（需要使用魔法）" value="groq-turbo" />
                <el-option-group label="本地 Whisper">
                  <el-option label="本地 base" value="local-base" />
                  <el-option label="本地 small" value="local-small" />
                  <el-option label="本地 medium" value="local-medium" />
                  <el-option label="本地 large-v3" value="local-large-v3" />
                </el-option-group>
              </el-select>
            </el-form-item>
            <el-form-item label="源语言">
              <el-select v-model="sourceLang" style="width:100%">
                <el-option label="自动检测" value="auto" />
                <el-option label="日语" value="日语" />
                <el-option label="英语" value="英语" />
                <el-option label="韩语" value="韩语" />
                <el-option label="中文" value="中文" />
              </el-select>
            </el-form-item>
            <el-form-item label="目标语言">
              <el-select v-model="targetLang" style="width:100%">
                <el-option label="中文" value="中文" />
                <el-option label="英语" value="英语" />
                <el-option label="日语" value="日语" />
                <el-option label="韩语" value="韩语" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="needsAsr">
              <el-checkbox v-model="bilingual">双语字幕</el-checkbox>
            </el-form-item>
            <el-form-item label="套用术语库">
              <GlossaryPicker v-model="glossaryIds" />
              <div class="tip">选中术语库中的术语将套用到本次翻译</div>
            </el-form-item>
            <el-form-item label="新词存入">
              <el-select v-model="termProjectId" style="width:100%" placeholder="按内容领域自动归类">
                <el-option label="按内容领域自动归类（推荐）" :value="null" />
                <el-option v-for="p in termProjects" :key="p.id"
                           :label="p.name + '（' + p.termCount + ' 条）'" :value="p.id" />
              </el-select>
              <div class="tip">
                本次抽出的新术语存到哪个库。做系列内容时选同一个库，人名/招式/专业词全系列统一。
              </div>
            </el-form-item>
            <el-form-item label="翻译风格">
              <el-switch v-model="styleEnabled" />
              <template v-if="styleEnabled">
                <div class="style-presets">
                  <el-tag v-for="p in stylePresets" :key="p.label" size="small" effect="plain"
                          class="style-tag" @click="stylePrompt = p.prompt">{{ p.label }}</el-tag>
                </div>
                <el-input v-model="stylePrompt" type="textarea" :rows="2" maxlength="500" show-word-limit
                          placeholder="描述期望的翻译风格（术语表优先于风格）" />
              </template>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="submitting" @click="submit">开始翻译</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <!-- 右：运行现场（agent 感的核心） -->
        <el-card class="panel">
          <div slot="header" class="card-head">
            <span>运行现场{{ current ? '（任务 #' + current.id + '）' : '' }}</span>
            <span v-if="detail.totalTokens" class="tokens">{{ detail.totalTokens }} token</span>
          </div>

          <el-empty v-if="!current" description="提交任务后这里实时展示每个专家在做什么" :image-size="80" />

          <template v-else>
            <el-tabs v-model="tab" class="run-tabs">
              <el-tab-pane label="现场" name="feed">
                <div ref="feed" class="feed">
                  <div v-for="item in feedItems" :key="item.seq"
                       class="feed-item" :class="{ fresh: item.seq > seenSeq, degraded: item.degraded }">
                    <span class="feed-icon">{{ item.icon }}</span>
                    <div class="feed-main">
                      <div class="feed-title">
                        <b>{{ item.title }}</b>
                        <el-tag v-if="item.profile" size="mini" effect="plain">{{ item.profile }}</el-tag>
                        <span class="feed-time">{{ item.elapsed }}</span>
                        <el-tag v-if="item.degraded" size="mini" type="warning" effect="plain">降级</el-tag>
                      </div>
                      <div v-if="item.detail" class="feed-detail" :class="{ links: item.node === 'SEARCH' }">
                        <!-- 搜索条目：把网址渲染成可点链接（只显示域名），用户能看到模型查了哪些网页 -->
                        <template v-if="item.node === 'SEARCH'">
                          <template v-for="(p, i) in linkify(item.detail)">
                            <a v-if="p.href" :key="i" :href="p.href" target="_blank" rel="noopener">{{ p.text }}</a>
                            <span v-else :key="i">{{ p.text }}</span>
                          </template>
                        </template>
                        <template v-else>{{ item.detail }}</template>
                      </div>
                    </div>
                  </div>

                  <!-- 进行中：打字机式指示器 -->
                  <div v-if="!isFinal(current.status)" class="feed-item running">
                    <span class="feed-icon spin"><i class="el-icon-loading" /></span>
                    <div class="feed-main">
                      <div class="feed-title"><b>{{ runningText }}</b><span class="dots"><i>.</i><i>.</i><i>.</i></span></div>
                      <el-progress :percentage="current.progress || 0" :stroke-width="6" :show-text="false"
                                   style="margin-top:6px" />
                    </div>
                  </div>
                  <div v-else-if="current.status === 'DONE'" class="feed-item done-line">
                    <span class="feed-icon">✅</span>
                    <div class="feed-main"><div class="feed-title"><b>完成</b>
                      <el-tag v-if="detail.agentDegraded === 1" size="mini" type="warning" effect="plain">部分步骤降级</el-tag>
                    </div></div>
                  </div>
                  <div v-else class="feed-item degraded">
                    <span class="feed-icon">❌</span>
                    <div class="feed-main"><div class="feed-title"><b>失败</b></div>
                      <div class="feed-detail errmsg">{{ detail.errorMsg || current.errorMsg }}</div></div>
                  </div>
                </div>
              </el-tab-pane>

              <el-tab-pane :label="'术语' + (detail.terms && detail.terms.length ? '（' + detail.terms.length + '）' : '')" name="terms">
                <el-table v-if="detail.terms && detail.terms.length" :data="detail.terms"
                          size="mini" stripe max-height="380">
                  <el-table-column prop="sourceTerm" label="原文" width="140" show-overflow-tooltip />
                  <el-table-column prop="targetTerm" label="译法" width="140" show-overflow-tooltip />
                  <el-table-column label="说明" show-overflow-tooltip>
                    <template slot-scope="{ row }"><span>{{ row.note || row.evidence || '—' }}</span></template>
                  </el-table-column>
                </el-table>
                <el-empty v-else description="暂无术语" :image-size="60" />
                <div class="tip">
                  可信度达标的术语已自动入库并在翻译中套用，其余仅本次使用、不进术语库。
                  到「术语库」页可随时修改，你改过的条目之后不会再被自动覆盖。
                </div>
              </el-tab-pane>
            </el-tabs>
          </template>
        </el-card>
      </div>

      <!-- 任务列表 -->
      <el-card class="panel">
        <div slot="header">我的全能翻译任务</div>
        <el-table :data="agentTasks" size="small" stripe highlight-current-row @current-change="onSelect">
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column prop="originalFilename" label="文件" show-overflow-tooltip />
          <el-table-column label="状态" width="200">
            <template slot-scope="{ row }">
              <el-tag size="mini" :type="tagType(row.status)">{{ statusText(row.status) }}</el-tag>
              <el-progress v-if="!isFinal(row.status)" :percentage="row.progress || 0" :stroke-width="6" />
              <span v-if="row.agentPhase && !isFinal(row.status)" class="phase-sm">{{ row.agentPhase }}</span>
              <span v-if="row.status === 'FAILED'" class="errmsg">{{ row.errorMsg }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="440">
            <template slot-scope="{ row }">
              <el-button v-if="row.status === 'DONE' && row.mediaType !== 'TEXT'" type="text"
                         icon="el-icon-edit-outline" @click="$router.push(`/editor/${row.id}`)">编辑字幕</el-button>
              <el-button v-if="row.status === 'DONE'" type="text" @click="openCompare(row)">双语对照</el-button>
              <el-button v-if="row.status === 'DONE'" type="text" @click="copyResult(row)">复制译文</el-button>
              <el-dropdown v-if="row.status === 'DONE'" trigger="click" class="more-dd"
                           @command="cmd => onTextCmd(cmd, row)">
                <el-button type="text">更多<i class="el-icon-arrow-down el-icon--right" /></el-button>
                <el-dropdown-menu slot="dropdown">
                  <el-dropdown-item command="view">网页查看译文</el-dropdown-item>
                  <el-dropdown-item command="exportPlain">导出译文</el-dropdown-item>
                  <el-dropdown-item command="exportPair">导出对照</el-dropdown-item>
                  <el-dropdown-item v-if="row.mediaType !== 'TEXT'" command="srt" divided>下载SRT</el-dropdown-item>
                </el-dropdown-menu>
              </el-dropdown>
              <el-button v-if="isFinal(row.status)" type="text" @click="retry(row)">
                {{ row.status === 'DONE' ? '重译' : '重试' }}
              </el-button>
              <el-button type="text" class="del" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!agentTasks.length" description="还没有任务，从左边上传一个文件试试" :image-size="80" />
      </el-card>
    </div>

    <!-- 双语对照弹窗 -->
    <el-dialog title="双语对照" :visible.sync="compareVisible" width="760px" top="6vh">
      <div v-loading="pairLoading" class="pair-box">
        <div v-for="(p, i) in pairRows" :key="i" class="pair-row">
          <div class="pair-src">{{ p.source }}</div>
          <div class="pair-tgt">{{ p.target }}</div>
        </div>
        <el-empty v-if="!pairLoading && !pairRows.length" description="暂无对照数据" :image-size="60" />
      </div>
      <span slot="footer">
        <el-button size="small" @click="exportPairs('pair')">导出对照</el-button>
        <el-button size="small" type="primary" @click="compareVisible = false">关闭</el-button>
      </span>
    </el-dialog>

    <!-- 我的专家 -->
    <el-dialog title="我的领域专家" :visible.sync="expertsVisible" width="720px">
      <div class="tip" style="margin-bottom:10px">
        提交任务后系统自动判断内容领域、挑选匹配的专家去抽术语。你自建的专家会加入候选池，
        内容对得上就会被选中——不需要手动指定。
      </div>
      <template v-if="!editingExpert">
        <el-table :data="experts" size="small" stripe max-height="380">
          <el-table-column prop="name" label="专家" width="140" show-overflow-tooltip />
          <el-table-column label="来源" width="70">
            <template slot-scope="{ row }">
              <el-tag size="mini" :type="row.builtin ? 'info' : 'success'">{{ row.builtin ? '内置' : '自建' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="擅长判断" show-overflow-tooltip>
            <template slot-scope="{ row }">{{ row.judgeCriteria || '—' }}</template>
          </el-table-column>
          <el-table-column label="启用" width="60">
            <template slot-scope="{ row }">
              <el-switch v-if="!row.builtin" :value="row.enabled === 1"
                         @change="v => toggleExpert(row, v)" />
              <el-tag v-else size="mini" effect="plain">常开</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="" width="100">
            <template slot-scope="{ row }">
              <el-button type="text" size="mini" @click="editExpert(row)">{{ row.builtin ? '查看' : '编辑' }}</el-button>
              <el-button v-if="!row.builtin" type="text" size="mini" class="del" @click="removeExpert(row)">删</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div style="margin-top:10px">
          <el-button type="primary" size="small" icon="el-icon-plus" @click="newExpert">新建专家</el-button>
        </div>
      </template>

      <template v-else>
        <el-form label-width="110px">
          <el-form-item label="专家名称">
            <el-input v-model="expertForm.name" :disabled="expertForm.builtin"
                      placeholder="如：机甲动画、医疗剧、王者荣耀" maxlength="30" show-word-limit />
          </el-form-item>
          <el-form-item label="什么内容归他">
            <el-input v-model="expertForm.judgeCriteria" type="textarea" :rows="2" :disabled="expertForm.builtin"
                      placeholder="帮系统判断：什么样的视频该派这位专家？如：出现机体名、驾驶员、出击等词的机器人动画"
                      maxlength="500" show-word-limit />
          </el-form-item>
          <el-form-item label="翻译惯例">
            <el-input v-model="expertForm.conventions" type="textarea" :rows="4" :disabled="expertForm.builtin"
                      placeholder="这个领域的词该怎么译？如：机体名保留英文原名；招式名用官方中文译名；人名按台版翻译"
                      maxlength="2000" show-word-limit />
          </el-form-item>
          <el-form-item label="搜索关键词">
            <el-input v-model="expertForm.searchHints" :disabled="expertForm.builtin"
                      placeholder="联网核实译法时附加的词，分号分隔。如：高达wiki；官方译名" maxlength="500" show-word-limit />
          </el-form-item>
        </el-form>
        <div style="text-align:right">
          <el-button size="small" @click="editingExpert = false">返回</el-button>
          <el-button v-if="!expertForm.builtin" type="primary" size="small"
                     :loading="savingExpert" @click="saveExpert">保存</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import http from '../api/http'
import stylePresetsMixin from '../mixins/stylePresets'
import taskOps from '../mixins/taskOps'
import GlossaryPicker from '../components/GlossaryPicker.vue'
import { statusText, isFinal, tagType } from '../constants/status'
import { blobDown } from '../utils/download'
import { copyText, openTextPage, downloadText, pairsToPlain, pairsToCompare } from '../utils/textActions'

/** 领域档案 code → 中文名（与后端内置档案种子一致；自建档案直接显示 name） */
const DOMAIN_TEXT = {
  general: '通用', it: 'IT/科技', medical: '医疗', legal: '法律', game: '游戏/动漫'
}
/** Trace 节点 → 现场文案与图标 */
const NODE_FEED = {
  SCENE: { icon: '🧭', title: '识别内容领域' },
  SUBAGENT: { icon: '🧠', title: '专家抽词' },
  SEARCH: { icon: '🔍', title: '联网核实' },
  ARBITRATE: { icon: '⚖️', title: '汇总仲裁' },
  TERMS: { icon: '📚', title: '术语入库' },
  VECTOR: { icon: '🧬', title: '近义发现' },
  TRANSLATE: { icon: '✍️', title: '带术语翻译' },
  PIPELINE: { icon: '⚙️', title: '流程' }
}

export default {
  name: 'AgentMode',
  components: { GlossaryPicker },
  mixins: [stylePresetsMixin, taskOps],
  data () {
    return {
      tasks: [],
      timer: null,
      rawFile: null,
      fileList: [],
      asrProvider: 'groq',
      sourceLang: 'auto',
      targetLang: '中文',
      bilingual: false,
      glossaryIds: [0],
      termProjectId: null,                 // 新词入库目标；null = 按领域自动建桶
      termProjects: [],
      submitting: false,
      current: null,
      detail: {},
      tab: 'feed',
      seenSeq: 0,                          // 已看过的最大 trace 序号，新条目做入场动画
      // 双语对照弹窗
      compareVisible: false,
      pairLoading: false,
      pairRows: [],
      pairTask: null,
      // 我的专家
      expertsVisible: false,
      experts: [],
      editingExpert: false,
      savingExpert: false,
      expertForm: { id: null, name: '', judgeCriteria: '', conventions: '', searchHints: '', builtin: false }
    }
  },
  computed: {
    agentTasks () { return this.tasks.filter(t => t.mode === 'AGENT') },
    needsAsr () {
      if (!this.rawFile) return true
      return !/\.(txt|md|markdown|srt|vtt)$/i.test(this.rawFile.name || '')
    },
    /** trace → 现场条目（人话 + 图标） */
    feedItems () {
      const traces = this.detail.traces || []
      return traces.map(t => {
        const meta = NODE_FEED[t.node] || { icon: '·', title: t.node }
        return {
          seq: t.seq,
          node: t.node,
          icon: meta.icon,
          title: this.feedTitle(t, meta),
          profile: t.profileCode ? (DOMAIN_TEXT[t.profileCode] || t.profileCode) : '',
          detail: t.outputDigest || '',
          elapsed: this.fmtMs(t.elapsedMs),
          degraded: t.degraded === 1
        }
      })
    },
    runningText () {
      return (this.current && this.current.agentPhase) || statusText(this.current && this.current.status) || '处理中'
    }
  },
  mounted () {
    this.fetchTasks()
    this.loadTermProjects()
    this.timer = setInterval(this.fetchTasks, 2000)
  },
  beforeDestroy () { clearInterval(this.timer) },
  methods: {
    statusText, isFinal, tagType,
    /** 「新词存入」的候选库；拉不到只剩「自动归类」，不阻塞提交 */
    loadTermProjects () {
      http.get('/kb/projects').then(r => { this.termProjects = r.data || [] }).catch(() => {})
    },
    feedTitle (t, meta) {
      // SUBAGENT 的 input 里带着 extract/resolve 前缀，转成人话
      if (t.node === 'SUBAGENT') {
        const inp = t.inputDigest || ''
        if (inp.startsWith('extract')) return '专家通读全文，挑出候选术语'
        if (inp.startsWith('resolve')) return '专家核实译法、定稿'
      }
      if (t.node === 'SEARCH') return '联网查「' + (t.inputDigest || '') + '」'
      return meta.title
    },
    /** 结果的文本操作（2026-08 需求改版）：复用文本AI翻译的交互，数据来自字幕表。 */
    async loadPairs (row) {
      const r = await http.get(`/tasks/${row.id}/subtitles`)
      return (r.data || []).map(s => ({ source: s.sourceText || '', target: s.targetText || '' }))
    },
    async openCompare (row) {
      this.compareVisible = true
      this.pairLoading = true
      this.pairTask = row
      try {
        this.pairRows = await this.loadPairs(row)
      } catch (e) { this.pairRows = [] } finally { this.pairLoading = false }
    },
    async copyResult (row) {
      try {
        const pairs = await this.loadPairs(row)
        const ok = await copyText(pairsToPlain(pairs))
        ok ? this.$message.success('译文已复制') : this.$message.error('复制失败，请手动选择复制')
      } catch (e) { /* 拦截器已提示 */ }
    },
    async onTextCmd (cmd, row) {
      if (cmd === 'srt') return this.download(row.id)
      try {
        const pairs = await this.loadPairs(row)
        const name = (row.originalFilename || ('任务' + row.id)).replace(/\.[^.]+$/, '')
        if (cmd === 'view') openTextPage(`${name} · 译文`, pairsToPlain(pairs))
        else if (cmd === 'exportPlain') downloadText(`${name}-译文.txt`, pairsToPlain(pairs))
        else if (cmd === 'exportPair') downloadText(`${name}-对照.txt`, pairsToCompare(pairs))
      } catch (e) { /* 拦截器已提示 */ }
    },
    exportPairs () {
      const row = this.pairTask
      const name = row ? (row.originalFilename || ('任务' + row.id)).replace(/\.[^.]+$/, '') : '译文'
      downloadText(`${name}-对照.txt`, pairsToCompare(this.pairRows))
    },
    fmtMs (ms) {
      if (!ms) return ''
      return ms >= 1000 ? (ms / 1000).toFixed(1) + 's' : ms + 'ms'
    },
    /** SEARCH 摘要里的 URL 切成 文本/链接 片段（链接只显示域名，点开看全文） */
    linkify (s) {
      const parts = []
      const re = /(https?:\/\/[^\s｜]+)/g
      let last = 0
      let m
      while ((m = re.exec(s))) {
        if (m.index > last) parts.push({ text: s.slice(last, m.index) })
        let host = m[1]
        try { host = new URL(m[1]).host.replace(/^www\./, '') } catch (e) { /* 解析失败就显示原文 */ }
        parts.push({ text: host + ' ↗', href: m[1] })
        last = m.index + m[1].length
      }
      if (last < s.length) parts.push({ text: s.slice(last) })
      return parts
    },
    onFileChange (file, fileList) { this.rawFile = file.raw; this.fileList = fileList.slice(-1) },
    onRemove () { this.rawFile = null; this.fileList = [] },
    async submit () {
      if (!this.rawFile) return this.$message.warning('请先选择文件')
      this.submitting = true
      try {
        const fd = new FormData()
        fd.append('file', this.rawFile)
        fd.append('mode', 'AGENT')
        fd.append('sourceLang', this.sourceLang)
        fd.append('targetLang', this.targetLang)
        fd.append('asrProvider', this.asrProvider)
        fd.append('bilingual', this.bilingual)
        fd.append('stylePrompt', this.styleEnabled ? this.stylePrompt.trim() : '')
        const gids = this.glossaryIds.filter(x => x !== 0)
        if (gids.length) fd.append('glossaryProjectIds', gids.join(','))
        // 新词入库目标；不传后端就按内容领域自动建桶
        if (this.termProjectId) fd.append('projectId', this.termProjectId)
        const r = await http.post('/tasks', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
        this.$message.success('任务已提交')
        this.rawFile = null
        this.fileList = []
        this.seenSeq = 0
        this.tab = 'feed'
        await this.fetchTasks()
        this.loadTermProjects()          // 新建的自动桶要能立刻出现在下拉里
        const id = r.data && (r.data.taskId || r.data.id)
        const created = this.agentTasks.find(t => t.id === id)
        if (created) this.onSelect(created)
      } catch (e) { /* 拦截器已提示 */ } finally { this.submitting = false }
    },
    fetchTasks () {
      http.get('/tasks').then(r => {
        this.tasks = r.data || []
        if (this.current) {
          const fresh = this.tasks.find(t => t.id === this.current.id)
          if (fresh) {
            const wasRunning = !isFinal(this.current.status)
            this.current = fresh
            // 跑动中持续拉现场；刚跑完再拉最后一次，别停在半截
            if (wasRunning || !isFinal(fresh.status)) this.loadDetail()
          }
        }
      }).catch(() => {})
    },
    onSelect (row) {
      if (!row) return
      this.current = row
      this.seenSeq = 0
      this.loadDetail()
    },
    loadDetail () {
      if (!this.current) return
      http.get(`/tasks/${this.current.id}/agent`).then(r => {
        const prevMax = (this.detail.traces || []).reduce((m, t) => Math.max(m, t.seq || 0), 0)
        this.detail = r.data || {}
        this.seenSeq = prevMax
        this.$nextTick(() => {
          const el = this.$refs.feed
          if (el && this.tab === 'feed') el.scrollTop = el.scrollHeight
        })
      }).catch(() => {})
    },
    download (id) { blobDown(`/tasks/${id}/srt`, `subtitle-${id}.srt`) },
    retry (row) { this.retryTask(row, this.fetchTasks) },
    remove (row) {
      this.removeTask(row, () => {
        if (this.current && this.current.id === row.id) { this.current = null; this.detail = {} }
        this.fetchTasks()
      })
    },
    // ─── 我的专家 ───
    openExperts () {
      this.expertsVisible = true
      this.editingExpert = false
      this.loadExperts()
    },
    loadExperts () {
      http.get('/agent/profiles').then(r => { this.experts = r.data || [] }).catch(() => {})
    },
    newExpert () {
      this.expertForm = { id: null, name: '', judgeCriteria: '', conventions: '', searchHints: '', builtin: false }
      this.editingExpert = true
    },
    editExpert (row) {
      this.expertForm = { id: row.id, name: row.name, judgeCriteria: row.judgeCriteria || '',
        conventions: row.conventions || '', searchHints: row.searchHints || '', builtin: row.builtin }
      this.editingExpert = true
    },
    async saveExpert () {
      if (!this.expertForm.name.trim()) return this.$message.warning('请填专家名称')
      this.savingExpert = true
      try {
        const body = {
          name: this.expertForm.name,
          judgeCriteria: this.expertForm.judgeCriteria,
          conventions: this.expertForm.conventions,
          searchHints: this.expertForm.searchHints
        }
        if (this.expertForm.id) await http.put(`/agent/profiles/${this.expertForm.id}`, body)
        else await http.post('/agent/profiles', body)
        this.$message.success('已保存，之后的任务会自动考虑这位专家')
        this.editingExpert = false
        this.loadExperts()
      } catch (e) { /* ignore */ } finally { this.savingExpert = false }
    },
    async toggleExpert (row, on) {
      try {
        await http.put(`/agent/profiles/${row.id}`, { enabled: on ? 1 : 0 })
        row.enabled = on ? 1 : 0
      } catch (e) { this.loadExperts() }
    },
    removeExpert (row) {
      this.$confirm(`删除专家「${row.name}」？已产出的术语不受影响。`, '提示', {
        confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
      }).then(async () => {
        try {
          await http.delete(`/agent/profiles/${row.id}`)
          this.$message.success('已删除')
          this.loadExperts()
        } catch (e) { /* ignore */ }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.body { max-width: 1100px; margin: 0 auto; display: flex; flex-direction: column; gap: 16px; }
.grid { display: grid; grid-template-columns: 380px 1fr; gap: 16px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.tip { color: #999; font-size: 12px; line-height: 1.6; }
.errmsg { color: #f56c6c; font-size: 12px; }
.tokens { color: #999; font-size: 12px; }
.score { color: #999; font-size: 11px; margin-left: 4px; }
.phase-sm { color: #909399; font-size: 11px; }
.run-tabs { margin-top: -6px; }

/* ── 运行现场 ── */
.feed { max-height: 430px; overflow-y: auto; padding: 4px 2px; }
.feed-item { display: flex; gap: 10px; padding: 7px 6px; border-radius: 6px; align-items: flex-start; }
.feed-item + .feed-item { border-top: 1px dashed #f0f0f0; }
.feed-item.fresh { animation: feedIn .4s ease; }
.feed-item.degraded .feed-title b { color: #e6a23c; }
.feed-item.done-line { background: #f0f9eb; }
.feed-icon { width: 22px; text-align: center; flex: none; font-size: 15px; line-height: 20px; }
.feed-icon.spin { color: #409eff; }
.feed-main { flex: 1; min-width: 0; }
.feed-title { font-size: 13px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.feed-time { color: #bbb; font-size: 11px; }
.feed-detail { color: #888; font-size: 12px; margin-top: 2px; word-break: break-all;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.feed-detail.links { -webkit-line-clamp: 6; }
.feed-detail a { color: #409eff; text-decoration: none; white-space: nowrap; }
.feed-detail a:hover { text-decoration: underline; }
.feed-item.running { background: #ecf5ff; border-radius: 6px; }
@keyframes feedIn { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: none; } }
.dots i { animation: blink 1.4s infinite both; font-style: normal; }
.dots i:nth-child(2) { animation-delay: .2s; }
.dots i:nth-child(3) { animation-delay: .4s; }
@keyframes blink { 0%, 80%, 100% { opacity: 0; } 40% { opacity: 1; } }
.more-dd { margin: 0 10px; }
.pair-box { max-height: 62vh; overflow-y: auto; }
.pair-row { padding: 8px 10px; border-bottom: 1px dashed #eef1f6; }
.pair-src { color: #8a94a6; font-size: 13px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.pair-tgt { color: #26303e; font-size: 14px; line-height: 1.8; white-space: pre-wrap; word-break: break-word; }
</style>
