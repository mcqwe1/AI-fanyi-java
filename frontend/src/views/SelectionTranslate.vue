<template>
  <div class="page">
    <div class="page-head">
      <h2>划词翻译 <el-tag size="mini" type="primary" effect="plain">浏览器扩展</el-tag></h2>
      <p class="hint">在任意网页选中文字，点悬浮球或右键即翻，译文直接替换原文；还支持整页翻译、图片翻译、鼠标悬停翻译和快捷键。</p>
    </div>

    <!-- 状态卡：扩展安装 / 连接（本页打开期间自动签发长效 token，扩展自动读取完成连接） -->
    <el-card class="status-card">
      <div class="status-row">
        <span class="status-item">
          <span class="dot" :class="extInstalled ? 'on' : ''" />
          扩展状态：<b>{{ extInstalled ? '已安装（v' + extVersion + '）' : '未检测到' }}</b>
          <span v-if="!extInstalled" class="sub">安装扩展后回到本页即可自动识别</span>
        </span>
        <span class="status-item">
          <span class="dot" :class="extPaired ? 'on' : (tokenReady ? 'wait' : '')" />
          连接状态：<b>{{ pairText }}</b>
        </span>
      </div>
      <div class="status-tip" v-if="extInstalled && extPaired">
        ✅ 一切就绪！去任意网页选中一段文字，点旁边弹出的悬浮球（或右键「狐译」）试试。目标语言在扩展图标弹窗里设置。
      </div>
    </el-card>

    <div class="two-col">
      <!-- 安装步骤 -->
      <el-card>
        <div class="card-title"><i class="el-icon-download" /> 安装扩展（只需一次，约 1 分钟）</div>
        <ol class="steps">
          <li>
            <b>获取扩展文件夹</b><br>
            <el-button size="small" type="primary" icon="el-icon-download" @click="downloadZip">下载扩展包并解压</el-button>
            <div class="sub" style="margin-top:6px">便携版用户也可以直接用安装目录里现成的 <code>extension</code> 文件夹（与 start.bat 同级），跳过下载。</div>
          </li>
          <li>
            <b>打开浏览器扩展管理页</b><br>
            <span class="sub">Chrome 地址栏输入 <code>chrome://extensions</code>，Edge 输入 <code>edge://extensions</code>，回车</span>
          </li>
          <li>
            <b>开启右上角（Edge 在左侧）的「开发者模式」开关</b>
          </li>
          <li>
            <b>点「加载已解压的扩展程序」，选择第 1 步的文件夹</b><br>
            <span class="sub">工具栏出现蓝色「灵」图标即安装成功</span>
          </li>
          <li>
            <b>回到本页，上方显示「已连接」即可使用</b><br>
            <span class="sub">安装扩展前已打开的网页需刷新一次，右键菜单才会出现</span>
          </li>
        </ol>
      </el-card>

      <!-- 使用方法 -->
      <el-card>
        <div class="card-title"><i class="el-icon-mouse" /> 使用方法</div>
        <ul class="usage">
          <li><b>划词翻译</b>：网页上选中文字 → 点旁边弹出的<b>悬浮球</b>，或右键 → <b>狐译（划词翻译）</b>，或按 <code>Alt+T</code>；译文展示方式可在「狐」图标弹窗里选：<b>替换原文 / 显示在原文下方 / 弹窗展示</b></li>
          <li><b>输入框翻译</b>：在任意输入框 / 文本框 / 富文本编辑器里打好字后，<b>连按三下空格</b>，输入内容就地翻成目标语言（可在弹窗里关闭）</li>
          <li><b>图片翻译</b>：网页图片上右键 → <b>狐译（翻译这张图片）</b>，本地 OCR 识别图中文字（中英文，完全离线）后用你配置的翻译模型翻译，译文写回图片原位；结果浮层<b>可拖动</b>，位置会被记住，不会挡住图片</li>
          <li><b>悬停翻译</b>：按 <code>Alt+H</code> 或在「狐」图标弹窗里开启后，鼠标停在段落上约半秒即自动翻译该段</li>
          <li><b>整页翻译</b>：按 <code>Alt+P</code>，或网页空白处右键 → <b>狐译（翻译整个网页）</b>；已翻过的内容会被标记跳过，网页后续<b>动态加载</b>出来的新内容会自动追译，不重复烧大模型</li>
          <li><b>原文 / 译文切换</b>：按 <code>Alt+R</code>（或右键菜单、弹窗按钮）在原文和译文之间来回切，译文<b>本地缓存</b>，切回译文不再调用大模型</li>
          <li><b>目标语言</b>：点工具栏「狐」图标，在弹窗里选择（默认中文，对所有网页生效）</li>
          <li><b>快捷键</b>：以上默认键可在 <code>chrome://extensions/shortcuts</code>（Edge 为 <code>edge://extensions/shortcuts</code>）修改，弹窗里也有入口</li>
        </ul>
        <el-divider />
        <div class="notes">
          <div><i class="el-icon-info" /> 翻译用的是你在「设置」里配好的翻译模型和密钥，本机狐译需保持运行。</div>
          <div><i class="el-icon-info" /> 扩展连接有效期 90 天，过期后重新打开本页即自动续期。</div>
          <div><i class="el-icon-info" /> 狐译客户端重启后扩展会自动重连，已打开的网页无需刷新即可继续使用。</div>
        </div>
      </el-card>
    </div>

    <!-- 划词翻译自己的历史：与「文本 AI 翻译」历史彻底分开，互不污染 -->
    <el-card class="hist-card">
      <div slot="header" class="hist-head">
        <span>翻译历史<span class="sub">　划词 / 整页 / 图片 / 输入框翻译的记录都在这里，不会进入「文本 AI 翻译」的历史</span></span>
        <span>
          <el-button size="mini" icon="el-icon-refresh" @click="fetchHistory">刷新</el-button>
          <el-button size="mini" class="del" icon="el-icon-delete" :disabled="!history.length"
                     @click="clearHistory">清空</el-button>
        </span>
      </div>
      <el-table :data="history" size="small" stripe v-loading="histLoading">
        <el-table-column label="类型" width="96">
          <template slot-scope="{ row }">
            <el-tag size="mini" effect="plain" type="warning">{{ extChannelLabel(row.channel) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="140">
          <template slot-scope="{ row }">{{ fmtWhen(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="targetLang" label="译文语言" width="90" />
        <el-table-column prop="preview" label="原文摘要" min-width="200" show-overflow-tooltip />
        <el-table-column prop="model" label="模型" width="150" show-overflow-tooltip />
        <el-table-column label="操作" width="280">
          <template slot-scope="{ row }">
            <el-button type="text" @click="viewHistory(row)">查看</el-button>
            <el-button type="text" @click="exportHistory(row, 'plain')">导出译文</el-button>
            <el-button type="text" @click="exportHistory(row, 'pair')">导出对照</el-button>
            <el-button type="text" class="del" @click="removeHistory(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!histLoading && !history.length" :image-size="70"
                description="还没有划词翻译记录：去任意网页选中一段文字试试" />
    </el-card>

    <el-dialog title="划词翻译详情" :visible.sync="detailVisible" width="760px" top="6vh">
      <div v-loading="detailLoading" class="pair-box">
        <div v-for="(p, i) in detailPairs" :key="i" class="pair-row">
          <div class="pair-src">{{ p.source }}</div>
          <div class="pair-tgt">{{ p.target }}</div>
        </div>
        <el-empty v-if="!detailLoading && !detailPairs.length" description="暂无数据" :image-size="60" />
      </div>
    </el-dialog>
  </div>
</template>

<script>
import http from '../api/http'
import { extChannelLabel, fmtWhen } from '../utils/history'
import { downloadText, pairsToPlain, pairsToCompare } from '../utils/textActions'

const PAIR_KEY = 'lingyi_ext_token'

export default {
  name: 'SelectionTranslate',
  data () {
    return {
      extInstalled: false,
      extVersion: '',
      extPaired: false,
      tokenReady: false,
      timer: null,
      history: [],
      histLoading: false,
      detailVisible: false,
      detailLoading: false,
      detailPairs: []
    }
  },
  computed: {
    pairText () {
      if (this.extPaired) return '已连接，可以使用'
      if (!this.extInstalled) return '等待安装扩展'
      return this.tokenReady ? '连接中…（自动完成，无需操作）' : '正在准备连接凭证…'
    }
  },
  async mounted () {
    this.check()
    this.timer = setInterval(this.check, 1000)
    this.fetchHistory()
    // 每次进入本页都签发/续期长效 token，写入 localStorage，扩展内容脚本自动读取保存
    try {
      const r = await http.post('/ext/token')
      localStorage.setItem(PAIR_KEY, JSON.stringify({
        token: r.data.token,
        backend: location.origin,
        username: r.data.username,
        expiresAt: r.data.expiresAt
      }))
      this.tokenReady = true
    } catch (e) { /* 拦截器已提示 */ }
  },
  beforeDestroy () {
    clearInterval(this.timer)
  },
  methods: {
    extChannelLabel,
    fmtWhen,
    check () {
      const el = document.documentElement
      this.extVersion = el.getAttribute('data-lingyi-ext') || ''
      this.extInstalled = !!this.extVersion
      this.extPaired = el.getAttribute('data-lingyi-ext-paired') === '1'
    },
    downloadZip () {
      const token = localStorage.getItem('token') || ''
      window.open(location.origin + '/api/ext/download?access_token=' + encodeURIComponent(token), '_blank')
    },
    /* ---- 划词翻译历史（独立于文本 AI 翻译历史） ---- */
    async fetchHistory () {
      this.histLoading = true
      try {
        const r = await http.get('/ext/history')
        this.history = r.data || []
      } catch (e) { /* 拦截器已提示 */ } finally { this.histLoading = false }
    },
    async loadPairs (row) {
      const r = await http.get(`/ext/history/${row.id}`)
      return (r.data && r.data.lines) || []
    },
    async viewHistory (row) {
      this.detailVisible = true
      this.detailLoading = true
      try {
        this.detailPairs = await this.loadPairs(row)
      } catch (e) { this.detailPairs = [] } finally { this.detailLoading = false }
    },
    async exportHistory (row, mode) {
      try {
        const pairs = await this.loadPairs(row)
        const name = extChannelLabel(row.channel) + '-' + row.id
        if (mode === 'plain') downloadText(`${name}-译文.txt`, pairsToPlain(pairs))
        else downloadText(`${name}-对照.txt`, pairsToCompare(pairs))
      } catch (e) { /* 拦截器已提示 */ }
    },
    removeHistory (row) {
      this.$confirm(`删除这条${extChannelLabel(row.channel)}记录？`, '删除', {
        confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
      }).then(async () => {
        try {
          await http.delete(`/ext/history/${row.id}`)
          this.$message.success('已删除')
          this.fetchHistory()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    clearHistory () {
      this.$confirm('清空全部划词翻译历史？不影响「文本 AI 翻译」的历史。', '清空', {
        confirmButtonText: '清空', cancelButtonText: '取消', type: 'warning'
      }).then(async () => {
        try {
          const r = await http.delete('/ext/history')
          this.$message.success(`已清空 ${(r.data && r.data.count) || 0} 条`)
          this.fetchHistory()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.page-head h2 { margin: 6px 0 4px; }
.page-head .hint { margin: 0 0 14px; }

.status-card { margin-bottom: 14px; }
.status-row { display: flex; flex-wrap: wrap; gap: 10px 36px; }
.status-item { display: inline-flex; align-items: center; gap: 7px; font-size: 14px; }
.status-item .sub { margin-left: 4px; }
.dot { width: 9px; height: 9px; border-radius: 50%; background: #cbd5e1; }
.dot.on { background: #22c55e; box-shadow: 0 0 0 3px rgba(34, 197, 94, .15); }
.dot.wait { background: #f59e0b; box-shadow: 0 0 0 3px rgba(245, 158, 11, .15); }
.status-tip { margin-top: 12px; padding: 9px 12px; border-radius: 8px; background: #f0fdf4; color: #166534; font-size: 13px; }

.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; align-items: start; }
@media (max-width: 1100px) { .two-col { grid-template-columns: 1fr; } }

.card-title { font-weight: 700; margin-bottom: 12px; font-size: 15px; }
.card-title i { color: var(--brand-deep); margin-right: 4px; }

.steps { margin: 0; padding-left: 20px; }
.steps li { margin-bottom: 14px; line-height: 1.7; }
.sub { color: #94a3b8; font-size: 12px; }
code {
  background: #f1f5f9; border: 1px solid #e2e8f0; border-radius: 4px;
  padding: 1px 6px; font-size: 12px; color: #475569;
}

.usage { margin: 0; padding-left: 20px; }
.usage li { margin-bottom: 10px; line-height: 1.8; }
.notes { color: #64748b; font-size: 12.5px; line-height: 2; }
.notes i { color: #94a3b8; margin-right: 4px; }

.hist-card { margin-top: 14px; }
.hist-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.hist-head .sub { color: #94a3b8; font-size: 12px; font-weight: 400; }
.del { color: #f56c6c; }
.pair-box { max-height: 62vh; overflow-y: auto; }
.pair-row { padding: 8px 10px; border-bottom: 1px dashed #eef1f6; }
.pair-src { color: #8a94a6; font-size: 13px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.pair-tgt { color: #26303e; font-size: 14px; line-height: 1.8; white-space: pre-wrap; word-break: break-word; }
</style>
