<template>
  <div class="dash">
    <!-- Hero -->
    <div class="hero">
      <div>
        <h1>欢迎使用 <span class="grad-text">AI视频翻译</span></h1>
        <p>强大的 AI 翻译能力，支持多种格式与场景 ✨</p>
      </div>
      <div class="hero-art">
        <span class="blob b1" /><span class="blob b2" />
        <span class="chip c1"><i class="el-icon-video-play" /></span>
        <span class="chip c2">Aa</span>
        <span class="chip c3">文</span>
      </div>
    </div>

    <!-- 四张模式卡 -->
    <div class="mode-grid">
      <el-card v-for="m in modes" :key="m.title" class="mode-card" shadow="hover">
        <el-tag v-if="m.hot" size="mini" type="primary" class="hot">推荐</el-tag>
        <div class="mode-icon"><span class="icon-tile" :class="m.tile"><i :class="m.icon" /></span></div>
        <h3>{{ m.title }}</h3>
        <p class="desc">{{ m.desc }}</p>
        <el-button class="btn-mode" :class="m.btn" @click="$router.push(m.to)">
          开始翻译 <i class="el-icon-right" />
        </el-button>
        <div class="formats">支持格式：{{ m.formats }}</div>
      </el-card>
    </div>

    <!-- 为什么选择我们 -->
    <el-card class="why">
      <div class="why-title">为什么选择我们</div>
      <div class="why-grid">
        <div v-for="w in whys" :key="w.title" class="why-item">
          <span class="why-icon" :style="{ background: w.bg, color: w.color }"><i :class="w.icon" /></span>
          <div>
            <div class="why-name">{{ w.title }}</div>
            <div class="why-desc">{{ w.desc }}</div>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 最近任务 -->
    <el-card class="recent">
      <div class="recent-head">
        <span><i class="el-icon-tickets" /> 最近任务</span>
        <el-button type="text" @click="$router.push('/tasks')">查看全部 <i class="el-icon-arrow-right" /></el-button>
      </div>
      <el-table v-if="recent.length" :data="recent" size="small">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="originalFilename" label="文件" show-overflow-tooltip />
        <el-table-column label="类型" width="80">
          <template slot-scope="{ row }">
            <el-tag size="mini" :type="row.mediaType === 'AUDIO' ? 'warning' : ''" effect="plain">
              {{ row.mediaType === 'AUDIO' ? '音频' : '视频' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="targetLang" label="目标语言" width="90" />
        <el-table-column label="状态" width="140">
          <template slot-scope="{ row }">
            <el-tag size="mini" :type="tagType(row.status)">{{ statusText(row.status) }}</el-tag>
            <el-progress v-if="!isFinal(row.status)" :percentage="row.progress || 0" :stroke-width="5" />
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="还没有任务，从上面选一个模式开始吧" :image-size="72" />
    </el-card>

    <setup-wizard :visible.sync="wizardVisible" />
  </div>
</template>

<script>
import http from '../api/http'
import SetupWizard from '../components/SetupWizard.vue'
import { statusText, isFinal, tagType } from '../constants/status'

export default {
  name: 'HomeDashboard',
  components: { SetupWizard },
  data () {
    return {
      wizardVisible: false,
      recent: [],
      timer: null,
      modes: [
        {
          title: '普通AI视频翻译', to: '/normal', hot: true,
          icon: 'el-icon-video-camera-solid', tile: 'tile-blue', btn: 'btn-blue',
          desc: '上传视频，AI自动识别语音并翻译生成双语字幕',
          formats: 'MP4、MOV、AVI、MKV 等'
        },
        {
          title: '术语库AI视频翻译', to: '/kb',
          icon: 'el-icon-notebook-2', tile: 'tile-green', btn: 'btn-green',
          desc: '结合自定义术语库，提升专业内容翻译的准确性',
          formats: 'MP4、MOV、AVI、MKV 等'
        },
        {
          title: '文本AI翻译', to: '/text',
          icon: 'el-icon-document', tile: 'tile-purple', btn: 'btn-purple',
          desc: '输入或粘贴文本，AI自动翻译支持多种语言互译',
          formats: '文本、TXT 等'
        },
        {
          title: '音频AI翻译', to: '/audio',
          icon: 'el-icon-headset', tile: 'tile-orange', btn: 'btn-orange',
          desc: '上传音频，AI识别并翻译生成双语文本',
          formats: 'MP3、WAV、M4A、AAC 等'
        },
        {
          title: '全能AI翻译', to: '/agent',
          icon: 'el-icon-magic-stick', tile: 'tile-rose', btn: 'btn-rose',
          desc: '多个领域专家同时抽词核实，专业内容译得更准（实验）',
          formats: '视频、音频、文本都支持'
        }
      ],
      whys: [
        { title: '高精度识别', desc: '先进的 ASR 技术，准确识别多语言语音', icon: 'el-icon-lightning', bg: '#e0edff', color: '#3b82f6' },
        { title: '自然流畅翻译', desc: '基于大模型的翻译能力，语义理解更准确', icon: 'el-icon-chat-dot-round', bg: '#ede9fe', color: '#8b5cf6' },
        { title: '术语库支持', desc: '自定义术语库，确保专业领域翻译一致性', icon: 'el-icon-circle-check', bg: '#d9f5ea', color: '#10b981' },
        { title: '多格式支持', desc: '支持多种音视频格式，满足不同场景需求', icon: 'el-icon-menu', bg: '#ffefd9', color: '#f59e0b' }
      ]
    }
  },
  mounted () {
    this.maybeShowWizard()
    this.fetchRecent()
    this.timer = setInterval(this.fetchRecent, 3000)
  },
  beforeDestroy () { clearInterval(this.timer) },
  methods: {
    statusText, isFinal, tagType,
    maybeShowWizard () {
      if (localStorage.getItem('aifanyi.setupDismissed')) return
      http.get('/settings').then(r => {
        const d = r.data || {}
        const noAsr = !(d.groqApiKey && d.groqApiKey.set)
        const noLlm = !(d.llmApiKey && d.llmApiKey.set)
        if (noAsr && noLlm) this.wizardVisible = true
      }).catch(() => {})
    },
    fetchRecent () {
      http.get('/tasks').then(r => { this.recent = (r.data || []).slice(0, 5) }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.dash { max-width: 1280px; margin: 0 auto; }

.hero { display: flex; justify-content: space-between; align-items: center; padding: 26px 8px 30px; }
.hero h1 { font-size: 34px; margin: 0 0 10px; color: var(--text-main); }
.hero p { color: var(--text-sub); margin: 0; font-size: 15px; }
.hero-art { position: relative; width: 300px; height: 120px; }
.blob { position: absolute; border-radius: 50%; filter: blur(2px); opacity: .55; }
.b1 { width: 170px; height: 170px; right: 0; top: -40px; background: radial-gradient(circle at 30% 30%, #dbe7ff, #ece4ff); }
.b2 { width: 90px; height: 90px; right: 180px; top: 30px; background: radial-gradient(circle at 30% 30%, #e6e0ff, #f5f0ff); }
.chip {
  position: absolute; border-radius: 14px; color: #fff; font-weight: 700;
  display: inline-flex; align-items: center; justify-content: center;
  box-shadow: 0 8px 18px rgba(60, 80, 180, .25);
}
.c1 { width: 58px; height: 58px; right: 130px; top: 8px; font-size: 26px; background: linear-gradient(135deg, #60a5fa, #3b82f6); }
.c2 { width: 46px; height: 46px; right: 62px; top: -14px; font-size: 18px; background: linear-gradient(135deg, #818cf8, #4f46e5); }
.c3 { width: 46px; height: 46px; right: 24px; top: 52px; font-size: 18px; background: linear-gradient(135deg, #c084fc, #8b5cf6); }

.mode-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 18px; }
.mode-card { position: relative; text-align: center; padding: 6px 2px; }
.mode-card .hot { position: absolute; top: 12px; right: 12px; }
.mode-icon { margin-top: 10px; }
.mode-card h3 { margin: 14px 0 8px; font-size: 17px; color: var(--text-main); }
.mode-card .desc { color: var(--text-sub); font-size: 13px; min-height: 38px; margin: 0 4px 14px; line-height: 1.6; }
.formats { color: #a0aec0; font-size: 12px; margin-top: 12px; }

.why { margin-top: 20px; }
.why-title { font-weight: 700; font-size: 16px; color: var(--text-main); margin-bottom: 16px; }
.why-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; }
.why-item { display: flex; gap: 12px; align-items: flex-start; }
.why-icon {
  width: 44px; height: 44px; border-radius: 50%; flex-shrink: 0; font-size: 20px;
  display: inline-flex; align-items: center; justify-content: center;
}
.why-name { font-weight: 600; font-size: 14px; color: var(--text-main); }
.why-desc { color: var(--text-sub); font-size: 12px; margin-top: 4px; line-height: 1.6; }

.recent { margin-top: 20px; }
.recent-head { display: flex; justify-content: space-between; align-items: center; font-weight: 700; color: var(--text-main); margin-bottom: 6px; }
</style>
