<template>
  <div class="dash">
    <!-- 问候区 -->
    <div class="hello">
      <h1>你好，{{ username }} <span class="wave-emoji">👋</span></h1>
      <p>今天也要让世界听懂你的声音。</p>
    </div>

    <!-- 功能入口卡 -->
    <div class="mode-grid">
      <div v-for="m in modes" :key="m.title" class="mode-card" @click="$router.push(m.to)">
        <el-tag v-if="m.hot" size="mini" type="primary" class="hot">推荐</el-tag>
        <span class="icon-tile tile-sm" :class="m.tile"><i :class="m.icon" /></span>
        <div class="mode-body">
          <h3>{{ m.title }}</h3>
          <p class="desc">{{ m.desc }}</p>
        </div>
        <i class="el-icon-right mode-arrow" />
      </div>
    </div>

    <!-- 最近项目：封面卡片（2026-08 需求改版） -->
    <el-card class="recent">
      <div class="sec-head">
        <span>最近项目</span>
        <el-button type="text" @click="$router.push('/tasks')">查看全部 <i class="el-icon-arrow-right" /></el-button>
      </div>
      <div v-if="recent.length" class="recent-grid">
        <div v-for="h in recent" :key="h.kind + '-' + h.id" class="proj-card" @click="openItem(h)">
          <div class="proj-cover">
            <!-- 视频帧 / 音频波形 -->
            <img v-if="h.thumb && !thumbFailed[h.kind + h.id]" :src="thumbSrc(h)"
                 class="cover-img" :class="{ 'cover-wave': h.mediaType === 'AUDIO' }"
                 @error="onThumbError(h)">
            <!-- 文档/文本：按对应格式的显示方式渲染内容 -->
            <div v-else-if="h.previewText" class="cover-doc">
              <div class="doc-paper" :class="'fmt-' + (h.format || 'txt')" v-html="renderPreview(h)" />
            </div>
            <div v-else class="cover-fallback"><i :class="fallbackIcon(h)" /></div>

            <span class="type-badge">{{ cardType(h) }}</span>
            <span v-if="!isDoneStatus(h)" class="cover-status" :class="{ failed: h.status === 'FAILED' }">
              {{ h.status === 'FAILED' ? '失败' : '进行中 ' + (h.progress || 0) + '%' }}
            </span>
          </div>
          <div class="proj-name" :title="h.title">{{ h.title || '（无标题）' }}</div>
          <div class="proj-meta">
            <span class="meta-lang">{{ langPairText(h) }}</span>
            <span class="meta-date">{{ fmtWhen(h.createdAt) }}</span>
          </div>
        </div>
      </div>
      <el-empty v-else description="还没有任务，从上面选一个模式开始吧" :image-size="72" />
    </el-card>

    <!-- 使用统计 + 术语库概览 -->
    <div class="stat-row">
      <el-card class="stat-card">
        <div class="sec-head"><span>使用统计</span><span class="sec-tip">近 14 天</span></div>
        <div class="stat-nums">
          <div class="stat-item">
            <div class="stat-label">任务总数</div>
            <div class="stat-val">{{ stats.total }}</div>
          </div>
          <div class="stat-item">
            <div class="stat-label">已完成</div>
            <div class="stat-val">{{ stats.done }}</div>
          </div>
          <div class="stat-item">
            <div class="stat-label">本月新增</div>
            <div class="stat-val">{{ stats.month }}</div>
          </div>
        </div>
        <svg class="spark" viewBox="0 0 280 72" preserveAspectRatio="none">
          <defs>
            <linearGradient id="sparkFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stop-color="#6a8bff" stop-opacity=".35" />
              <stop offset="1" stop-color="#6a8bff" stop-opacity="0" />
            </linearGradient>
          </defs>
          <polygon :points="sparkArea" fill="url(#sparkFill)" />
          <polyline :points="sparkLine" fill="none" stroke="#5b7cfa" stroke-width="2"
                    stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </el-card>

      <el-card class="stat-card">
        <div class="sec-head"><span>术语库概览</span></div>
        <div class="gloss-wrap">
          <div class="gloss-info">
            <div class="gloss-line"><i class="el-icon-collection" /> 术语库项目 <b>{{ kbProjects.length }}</b> 个</div>
            <div class="gloss-line"><i class="el-icon-document-copy" /> 总术语数 <b>{{ termTotal }}</b></div>
            <el-button type="text" class="gloss-go" @click="$router.push('/glossary')">
              进入术语库 <i class="el-icon-right" />
            </el-button>
          </div>
          <svg class="donut" viewBox="0 0 132 132">
            <circle cx="66" cy="66" r="52" fill="none" stroke="#eef1f6" stroke-width="16" />
            <circle v-for="seg in donutSegs" :key="seg.key" cx="66" cy="66" r="52" fill="none"
                    :stroke="seg.color" stroke-width="16"
                    :stroke-dasharray="seg.dash" :stroke-dashoffset="seg.offset"
                    transform="rotate(-90 66 66)" stroke-linecap="butt" />
            <text x="66" y="62" text-anchor="middle" class="donut-num">{{ termTotal }}</text>
            <text x="66" y="82" text-anchor="middle" class="donut-label">总术语数</text>
          </svg>
        </div>
      </el-card>
    </div>

    <setup-wizard :visible.sync="wizardVisible" />
  </div>
</template>

<script>
import http from '../api/http'
import SetupWizard from '../components/SetupWizard.vue'
import { cardType, langPairText, fmtWhen, renderDocPreview } from '../utils/history'

const DONUT_COLORS = ['#5b7cfa', '#8b6cf6', '#38bdf8', '#34d399', '#fbbf24']

export default {
  name: 'HomeDashboard',
  components: { SetupWizard },
  data () {
    return {
      wizardVisible: false,
      tasks: [],
      history: [],
      kbProjects: [],
      thumbFailed: {},
      timer: null,
      modes: [
        {
          title: '音频/视频翻译', to: '/normal', hot: true,
          icon: 'el-icon-video-camera-solid', tile: 'tile-blue',
          desc: '上传视频或音频，AI 识别语音并翻译'
        },
        {
          title: '文本AI翻译', to: '/text',
          icon: 'el-icon-document', tile: 'tile-purple',
          desc: '精准翻译文本，支持多种语言互译'
        },
        {
          title: '文档AI翻译', to: '/doc',
          icon: 'el-icon-folder-opened', tile: 'tile-blue',
          desc: 'PDF/Word/ePub 等整篇翻译，保留原排版'
        },
        {
          title: '全能AI翻译', to: '/agent',
          icon: 'el-icon-magic-stick', tile: 'tile-rose',
          desc: '多领域专家协同，智能理解专业翻译'
        },
        {
          title: '划词翻译', to: '/selection',
          icon: 'el-icon-mouse', tile: 'tile-cyan',
          desc: '任意网页选中即翻，整页翻译直接替换'
        }
      ]
    }
  },
  computed: {
    username () { return this.$store.state.username || '狐译用户' },
    recent () { return this.history.slice(0, 8) },
    stats () {
      const now = new Date()
      let done = 0; let month = 0
      for (const t of this.tasks) {
        if (t.status === 'DONE') done++
        const c = t.createdAt && new Date(t.createdAt)
        if (c && c.getFullYear() === now.getFullYear() && c.getMonth() === now.getMonth()) month++
      }
      return { total: this.tasks.length, done, month }
    },
    dayCounts () {
      // 近 14 天每日任务数
      const days = []
      const today = new Date(); today.setHours(0, 0, 0, 0)
      for (let i = 13; i >= 0; i--) {
        days.push(new Date(today.getTime() - i * 86400000))
      }
      const counts = days.map(() => 0)
      for (const t of this.tasks) {
        if (!t.createdAt) continue
        const c = new Date(t.createdAt); c.setHours(0, 0, 0, 0)
        const idx = days.findIndex(d => d.getTime() === c.getTime())
        if (idx >= 0) counts[idx]++
      }
      return counts
    },
    sparkLine () {
      const c = this.dayCounts
      const max = Math.max(1, ...c)
      const stepX = 280 / (c.length - 1)
      return c.map((v, i) => `${(i * stepX).toFixed(1)},${(66 - (v / max) * 56).toFixed(1)}`).join(' ')
    },
    sparkArea () {
      return `0,72 ${this.sparkLine} 280,72`
    },
    termTotal () {
      return this.kbProjects.reduce((s, p) => s + (p.termCount || 0), 0)
    },
    donutSegs () {
      const total = this.termTotal
      if (!total) return []
      const C = 2 * Math.PI * 52
      // 前 4 个项目 + 其余合并，按术语数占比分段
      const sorted = [...this.kbProjects].filter(p => p.termCount > 0).sort((a, b) => b.termCount - a.termCount)
      const parts = sorted.slice(0, 4).map(p => p.termCount)
      const rest = sorted.slice(4).reduce((s, p) => s + p.termCount, 0)
      if (rest > 0) parts.push(rest)
      let acc = 0
      return parts.map((v, i) => {
        const len = (v / total) * C
        const seg = { key: i, color: DONUT_COLORS[i % DONUT_COLORS.length], dash: `${len} ${C - len}`, offset: -acc }
        acc += len
        return seg
      })
    }
  },
  mounted () {
    this.maybeShowWizard()
    this.fetchData()
    this.timer = setInterval(this.fetchData, 3000)
  },
  beforeDestroy () { clearInterval(this.timer) },
  methods: {
    cardType,
    langPairText,
    fmtWhen,
    maybeShowWizard () {
      if (localStorage.getItem('aifanyi.setupDismissed')) return
      http.get('/settings').then(r => {
        const d = r.data || {}
        const noAsr = !(d.groqApiKey && d.groqApiKey.set)
        const noLlm = !(d.llmApiKey && d.llmApiKey.set)
        if (noAsr && noLlm) this.wizardVisible = true
      }).catch(() => {})
    },
    fetchData () {
      http.get('/tasks').then(r => { this.tasks = r.data || [] }).catch(() => {})
      http.get('/history/all', { params: { limit: 16 } })
        .then(r => { this.history = r.data || [] }).catch(() => {})
      http.get('/kb/projects').then(r => { this.kbProjects = r.data || [] }).catch(() => {})
    },
    thumbSrc (h) {
      return h.thumb + '?access_token=' + encodeURIComponent(localStorage.getItem('token') || '')
    },
    onThumbError (h) {
      this.$set(this.thumbFailed, h.kind + h.id, true)
    },
    renderPreview (h) {
      return renderDocPreview(h.format, h.previewText)
    },
    isDoneStatus (h) {
      return !h.status || h.status === 'DONE' || h.status === 'SUCCESS'
    },
    fallbackIcon (h) {
      if (h.kind === 'doc') return 'el-icon-folder-opened'
      if (h.kind === 'text') return 'el-icon-document'
      return h.mediaType === 'AUDIO' ? 'el-icon-headset' : 'el-icon-video-camera'
    },
    openItem (h) {
      if (h.kind === 'doc') {
        this.$router.push(h.status === 'SUCCESS' ? `/doc/${h.id}/compare` : '/doc')
      } else if (h.kind === 'text') {
        this.$router.push('/text')
      } else {
        this.$router.push('/tasks')
      }
    }
  }
}
</script>

<style scoped>
.dash { max-width: 1280px; margin: 0 auto; }

.hello { padding: 18px 4px 24px; }
.hello h1 { font-size: 30px; margin: 0 0 8px; color: var(--text-main); }
.hello p { color: var(--text-sub); margin: 0; font-size: 14px; }
.wave-emoji { display: inline-block; animation: wave-hand 2.4s ease-in-out infinite; transform-origin: 70% 70%; }
@keyframes wave-hand {
  0%, 55%, 100% { transform: rotate(0); }
  60%, 75% { transform: rotate(18deg); }
  67%, 82% { transform: rotate(-8deg); }
}

.mode-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(270px, 1fr)); gap: 16px; }
.mode-card {
  position: relative; background: #fff; border: 1px solid #eef1f6; border-radius: var(--card-radius);
  box-shadow: var(--shadow); padding: 20px 18px 18px;
  display: flex; gap: 14px; align-items: flex-start; cursor: pointer;
  transition: transform .18s, box-shadow .18s;
}
.mode-card:hover { transform: translateY(-3px); box-shadow: 0 10px 28px rgba(60, 80, 180, .12); }
.mode-card .hot { position: absolute; top: 12px; right: 12px; }
.tile-sm { width: 46px; height: 46px; border-radius: 13px; font-size: 22px; flex-shrink: 0; }
.mode-body { min-width: 0; }
.mode-card h3 { margin: 2px 0 6px; font-size: 15px; color: var(--text-main); }
.mode-card .desc { color: var(--text-sub); font-size: 12.5px; margin: 0 22px 0 0; line-height: 1.6; }
.mode-arrow {
  position: absolute; right: 16px; bottom: 16px; color: #b6c1e0; font-size: 16px;
  transition: transform .18s, color .18s;
}
.mode-card:hover .mode-arrow { transform: translateX(3px); color: var(--brand-deep); }

.sec-head {
  display: flex; justify-content: space-between; align-items: center;
  font-weight: 700; color: var(--text-main); margin-bottom: 10px; font-size: 15px;
}
.sec-tip { color: #a0aec0; font-size: 12px; font-weight: 400; }

.recent { margin-top: 20px; }

/* ---- 最近项目封面卡片 ---- */
.recent-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(215px, 1fr)); gap: 16px;
}
.proj-card { cursor: pointer; min-width: 0; }
.proj-cover {
  position: relative; aspect-ratio: 16 / 9; border-radius: 12px; overflow: hidden;
  background: linear-gradient(135deg, #eef2ff, #f6f8ff);
  border: 1px solid #eef1f6;
  transition: transform .18s, box-shadow .18s;
}
.proj-card:hover .proj-cover { transform: translateY(-2px); box-shadow: 0 10px 24px rgba(60, 80, 180, .14); }
.cover-img { width: 100%; height: 100%; object-fit: cover; display: block; }
/* 音频波形：透明 PNG，衬深色渐变底更像「音轨」 */
.cover-img.cover-wave {
  object-fit: contain; padding: 14px 10px;
  background: linear-gradient(135deg, #1d2340, #2a3158);
}
.cover-doc { position: absolute; inset: 0; display: flex; justify-content: center; padding: 10px 12px 0; }
.doc-paper {
  width: 88%; background: #fff; border-radius: 6px 6px 0 0;
  box-shadow: 0 2px 10px rgba(30, 40, 90, .10);
  padding: 10px 12px; font-size: 10px; line-height: 1.65; color: #3a4358;
  overflow: hidden; word-break: break-all; white-space: pre-wrap;
}
.doc-paper.fmt-json, .doc-paper.fmt-srt, .doc-paper.fmt-vtt, .doc-paper.fmt-ass {
  font-family: Consolas, Menlo, monospace; font-size: 9.5px; color: #445;
}
.doc-paper :deep(h5) { margin: 0 0 3px; font-size: 11px; color: #1f2940; }
.doc-paper :deep(b) { color: #1f2940; }
.doc-paper :deep(li) { margin-left: 12px; }
.cover-fallback {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
  color: #b6c1e0; font-size: 40px;
}
.type-badge {
  position: absolute; top: 8px; left: 8px;
  background: rgba(23, 28, 48, .72); color: #fff;
  font-size: 11px; padding: 3px 9px; border-radius: 8px; backdrop-filter: blur(3px);
}
.cover-status {
  position: absolute; right: 8px; bottom: 8px;
  background: rgba(250, 173, 20, .92); color: #fff;
  font-size: 11px; padding: 2px 8px; border-radius: 7px;
}
.cover-status.failed { background: rgba(245, 108, 108, .92); }
.proj-name {
  margin: 9px 2px 2px; text-align: left;
  font-size: 13.5px; font-weight: 600; color: var(--text-main);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.proj-meta {
  display: flex; justify-content: space-between; align-items: center;
  margin: 0 2px; font-size: 11.5px; color: rgba(122, 134, 154, .72);
}
.meta-lang { text-align: left; }
.meta-date { text-align: right; }

.stat-row { display: grid; grid-template-columns: 3fr 2fr; gap: 16px; margin-top: 20px; }
@media (max-width: 980px) { .stat-row { grid-template-columns: 1fr; } }

.stat-nums { display: flex; gap: 36px; margin: 6px 0 14px; }
.stat-label { color: var(--text-sub); font-size: 12px; margin-bottom: 4px; }
.stat-val { font-size: 26px; font-weight: 700; color: var(--text-main); }
.spark { width: 100%; height: 72px; display: block; }

.gloss-wrap { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.gloss-info { min-width: 0; }
.gloss-line { color: var(--text-sub); font-size: 13px; margin: 10px 0; }
.gloss-line b { color: var(--text-main); font-size: 16px; margin-left: 2px; }
.gloss-line i { margin-right: 6px; color: #8b9bc6; }
.gloss-go { padding: 4px 0; }
.donut { width: 132px; height: 132px; flex-shrink: 0; }
.donut-num { font-size: 24px; font-weight: 700; fill: var(--text-main); }
.donut-label { font-size: 11px; fill: #a0aec0; }
</style>
