<template>
  <div class="help-page">
    <!-- ============ 左侧导航 ============ -->
    <aside class="side">
      <div class="brand" @click="goHome">
        <img :src="logo" class="brand-logo" alt="狐译">
        <span class="brand-name">狐译</span>
        <el-tag size="mini" effect="plain" class="brand-tag">文档中心</el-tag>
      </div>

      <el-input v-model="keyword" size="small" class="side-search" clearable
                prefix-icon="el-icon-search" placeholder="搜索文档" @input="onSearch" />

      <nav class="side-nav">
        <template v-for="cat in categories">
          <div :key="'g-' + cat.id" class="nav-group">{{ cat.name }}</div>
          <a v-for="d in docsOf(cat.id)" :key="cat.id + '-' + d.id" class="nav-item"
             :class="{ active: current && current.id === d.id }" @click="openDoc(d.id)">
            <i :class="cat.icon" />
            <span>{{ d.title }}</span>
          </a>
        </template>
      </nav>

      <div class="side-help">
        <img :src="logo" class="side-help-img" alt="">
        <div class="side-help-title">需要帮助?</div>
        <div class="side-help-sub">智能客服随时为你服务</div>
        <el-button size="mini" type="primary" plain round @click="openChat">
          联系在线客服 <i class="el-icon-right" /></el-button>
      </div>
    </aside>

    <!-- ============ 主内容 ============ -->
    <main class="main" ref="main">
      <div class="topbar">
        <span class="topbar-left">
          <el-button type="text" icon="el-icon-back" class="back" @click="back">
            {{ current || searching ? '返回目录' : '返回狐译' }}</el-button>
          <b>帮助与反馈</b>
        </span>
        <span class="topbar-right">
          <el-input v-model="keyword" size="small" class="top-search" clearable
                    prefix-icon="el-icon-search" placeholder="搜索文档、关键词…" @input="onSearch" />
          <el-button type="primary" size="small" icon="el-icon-edit-outline"
                     @click="feedbackDialog = true">反馈与建议</el-button>
        </span>
      </div>

      <!-- ---- 搜索结果 ---- -->
      <template v-if="searching">
        <div class="section-title">「{{ keyword }}」的搜索结果（{{ results.length }} 条）</div>
        <div v-if="!results.length" class="empty">没有找到相关内容，换个关键词试试，或直接问右下角的智能客服。</div>
        <div v-for="(r, i) in results" :key="i" class="result-row" @click="openDoc(r.doc.id, r.section.id)">
          <div class="result-title"><i class="el-icon-document" /> {{ r.doc.title }} · {{ r.section.h }}</div>
          <div class="result-snippet">{{ r.snippet }}</div>
        </div>
      </template>

      <!-- ---- 文章视图 ---- -->
      <template v-else-if="current">
        <div class="crumb">
          <span class="crumb-link" @click="goHome">帮助中心</span>
          <i class="el-icon-arrow-right" />
          <span class="crumb-link" @click="goHome">{{ catName(current.cat) }}</span>
          <i class="el-icon-arrow-right" />
          <span>{{ current.title }}</span>
        </div>
        <h1 class="doc-title">{{ current.title }}</h1>
        <div class="doc-meta">
          <span><i class="el-icon-time" /> 约 {{ current.minutes }} 分钟</span>
          <span>更新于 {{ current.updated }}</span>
        </div>
        <div v-for="s in current.sections" :id="'sec-' + s.id" :key="s.id" class="doc-section">
          <h2>{{ s.h }}</h2>
          <div class="doc-html" v-html="s.html" />
        </div>
        <div class="doc-foot">
          <span>这篇文档解决你的问题了吗？</span>
          <el-button size="mini" round @click="thumb(true)">👍 有帮助</el-button>
          <el-button size="mini" round @click="feedbackDialog = true">还有疑问，去反馈</el-button>
        </div>
      </template>

      <!-- ---- 首页视图 ---- -->
      <template v-else>
        <div class="hero">
          <div class="hero-text">
            <h1>欢迎使用 狐译 👋</h1>
            <p>快速上手狐译，掌握各项功能的使用方法</p>
            <el-input v-model="keyword" class="hero-search" clearable
                      prefix-icon="el-icon-search" placeholder="搜索教程、功能或问题…" @input="onSearch" />
          </div>
          <img :src="logo" class="hero-img" alt="">
        </div>

        <div class="section-title">推荐阅读</div>
        <div class="rec-grid">
          <div v-for="r in recommended" :key="r.doc" class="rec-card" @click="openDoc(r.doc)">
            <div class="rec-icon"><i :class="catIcon(docOf(r.doc).cat)" /></div>
            <div class="rec-title">{{ docOf(r.doc).title }}</div>
            <div class="rec-desc">{{ firstText(docOf(r.doc)) }}</div>
            <div class="rec-foot">
              <el-tag size="mini" :type="r.tagType" effect="plain">{{ r.tag }}</el-tag>
              <span class="rec-min">{{ r.minutes }} 分钟</span>
            </div>
          </div>
        </div>

        <div class="section-title">文档分类</div>
        <div class="cat-grid">
          <div v-for="c in categories" :key="c.id" class="cat-card" @click="openCat(c)">
            <div class="cat-icon"><i :class="c.icon" /></div>
            <div>
              <div class="cat-name">{{ c.name }}</div>
              <div class="cat-desc">{{ c.desc }}</div>
              <div class="cat-count">{{ docsOf(c.id).length }} 篇文档</div>
            </div>
          </div>
        </div>

        <div class="section-title">最近更新</div>
        <div class="upd-list">
          <div v-for="(u, i) in recentUpdates" :key="i" class="upd-row" @click="openDoc(u.doc)">
            <i class="el-icon-document-checked upd-ico" />
            <div class="upd-main">
              <div class="upd-title">{{ u.title }}</div>
            </div>
            <span class="upd-date">{{ u.date }}</span>
            <el-tag size="mini" effect="plain" :type="u.tag === '新功能' ? 'success' : (u.tag === 'FAQ' ? 'warning' : '')">
              {{ u.tag }}</el-tag>
          </div>
        </div>
        <div class="see-all" @click="openDoc('changelog')">查看全部更新 <i class="el-icon-right" /></div>
      </template>
    </main>

    <!-- ============ 右侧目录 ============ -->
    <aside class="toc">
      <div class="toc-title">本文目录</div>
      <template v-if="current">
        <a class="toc-h1">{{ current.title }}</a>
        <a v-for="s in current.sections" :key="s.id" class="toc-item"
           :class="{ active: activeSec === s.id }" @click="scrollTo(s.id)">{{ s.h }}</a>
      </template>
      <template v-else>
        <a class="toc-h1">欢迎使用狐译</a>
        <a class="toc-item" @click="openDoc('welcome', 'what')">什么是狐译?</a>
        <a class="toc-item" @click="openDoc('welcome', 'features')">主要功能</a>
        <a class="toc-item" @click="openDoc('welcome', 'scene')">适用场景</a>
        <a class="toc-item" @click="openDoc('welcome', 'require')">系统要求</a>
        <a class="toc-h1">快速开始</a>
        <a class="toc-item" @click="openDoc('quick-start', 'step1')">注册与登录</a>
        <a class="toc-item" @click="openDoc('quick-start', 'step2')">配置模型服务</a>
        <a class="toc-item" @click="openDoc('quick-start', 'step3')">创建第一个翻译任务</a>
        <a class="toc-h1">下一步</a>
        <a class="toc-item" @click="openDoc('features-tour')">深入了解功能</a>
        <a class="toc-item" @click="openDoc('faq')">常见问题</a>
        <a class="toc-item" @click="openChat">获取帮助</a>
      </template>
    </aside>

    <!-- ============ 反馈与建议 ============ -->
    <el-dialog title="反馈与建议" :visible.sync="feedbackDialog" width="480px" append-to-body>
      <p class="fb-tip">功能建议、问题反馈都欢迎。内容保存在本机，由管理员在后台查看。</p>
      <el-input v-model="fbContent" type="textarea" :rows="5" maxlength="2000" show-word-limit
                placeholder="说说你遇到的问题或想要的功能…" />
      <el-input v-model="fbContact" style="margin-top:10px" maxlength="120"
                placeholder="联系方式（选填，方便回复你）" />
      <div slot="footer">
        <el-button @click="feedbackDialog = false">取消</el-button>
        <el-button type="primary" :loading="fbSending" :disabled="!fbContent.trim()"
                   @click="sendFeedback">提交反馈</el-button>
      </div>
    </el-dialog>

    <!-- 智能客服（已登录时可用） -->
    <SupportChat v-if="hasToken" ref="chat" />
  </div>
</template>

<script>
import http from '../api/http'
import logo from '../assets/logo.png'
import SupportChat from '../components/SupportChat.vue'
import {
  HELP_CATEGORIES, HELP_DOCS, RECOMMENDED, RECENT_UPDATES,
  docById, docsByCat, searchDocs
} from '../constants/helpDocs'

export default {
  name: 'HelpCenter',
  components: { SupportChat },
  data () {
    return {
      logo,
      categories: HELP_CATEGORIES,
      recommended: RECOMMENDED,
      recentUpdates: RECENT_UPDATES,
      current: null,
      keyword: '',
      results: [],
      activeSec: '',
      feedbackDialog: false,
      fbContent: '',
      fbContact: '',
      fbSending: false
    }
  },
  computed: {
    searching () { return this.keyword.trim().length > 0 },
    hasToken () { return !!localStorage.getItem('token') }
  },
  watch: {
    '$route.query.doc' (v) { this.applyQuery(v, this.$route.query.sec) }
  },
  mounted () {
    this.applyQuery(this.$route.query.doc, this.$route.query.sec)
  },
  methods: {
    docsOf: docsByCat,
    docOf: docById,
    catName (id) {
      const c = this.categories.find(x => x.id === id)
      return c ? c.name : id
    },
    catIcon (id) {
      const c = this.categories.find(x => x.id === id)
      return c ? c.icon : 'el-icon-document'
    },
    firstText (doc) {
      if (!doc || !doc.sections.length) return ''
      return doc.sections[0].html.replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').slice(0, 42) + '…'
    },
    applyQuery (docId, secId) {
      if (docId && docById(docId)) {
        this.openDoc(docId, secId, true)
      }
    },
    openDoc (id, secId, silent) {
      const d = docById(id)
      if (!d) return
      this.keyword = ''
      this.results = []
      this.current = d
      this.activeSec = secId || (d.sections[0] && d.sections[0].id) || ''
      if (!silent && this.$route.query.doc !== id) {
        this.$router.replace({ query: { doc: id } }).catch(() => {})
      }
      this.$nextTick(() => {
        if (secId) {
          this.scrollTo(secId)
        } else if (this.$refs.main) {
          this.$refs.main.scrollTop = 0
        }
      })
    },
    openCat (cat) {
      const docs = docsByCat(cat.id)
      if (docs.length) this.openDoc(docs[0].id)
    },
    goHome () {
      this.current = null
      this.keyword = ''
      this.results = []
      if (this.$route.query.doc) this.$router.replace({ query: {} }).catch(() => {})
      this.$nextTick(() => { if (this.$refs.main) this.$refs.main.scrollTop = 0 })
    },
    back () {
      if (this.current || this.searching) return this.goHome()
      // 帮助中心通常在新标签页打开：回软件主页（未登录回登录页）
      this.$router.push(localStorage.getItem('token') ? '/' : '/login')
    },
    onSearch () {
      this.results = searchDocs(this.keyword)
      if (this.searching) this.current = null
    },
    scrollTo (secId) {
      this.activeSec = secId
      const el = document.getElementById('sec-' + secId)
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
    },
    openChat () {
      if (!this.hasToken) {
        this.$message.info('请先登录狐译，再使用智能客服')
        return
      }
      if (this.$refs.chat) this.$refs.chat.open = true
    },
    thumb (up) {
      this.$message.success(up ? '感谢反馈！' : '')
    },
    async sendFeedback () {
      this.fbSending = true
      try {
        await http.post('/feedback', { content: this.fbContent.trim(), contact: this.fbContact.trim() })
        this.$message.success('反馈已提交，感谢！')
        this.feedbackDialog = false
        this.fbContent = ''
        this.fbContact = ''
      } catch (e) { /* 拦截器已提示 */ } finally { this.fbSending = false }
    }
  }
}
</script>

<style scoped>
.help-page {
  display: flex; height: 100vh; overflow: hidden;
  background: #f7f8fc; color: #1f2d3d;
}

/* ============ 左侧 ============ */
.side {
  width: 240px; flex-shrink: 0; background: #fff;
  border-right: 1px solid #eef1f6; display: flex; flex-direction: column;
  padding: 16px 14px;
}
.brand { display: flex; align-items: center; gap: 8px; cursor: pointer; padding: 2px 4px 14px; }
.brand-logo { width: 34px; height: 34px; }
.brand-name {
  font-weight: 800; font-size: 18px; letter-spacing: 2px;
  background: linear-gradient(100deg, #3b82f6, #7c5cff);
  -webkit-background-clip: text; background-clip: text; color: transparent;
}
.brand-tag { flex-shrink: 0; }
.side-search { margin-bottom: 10px; }
.side-nav { flex: 1; overflow-y: auto; }
.nav-group { color: #a0aec0; font-size: 12px; margin: 14px 6px 6px; }
.nav-item {
  display: flex; align-items: center; gap: 8px; cursor: pointer;
  padding: 7px 8px; border-radius: 8px; font-size: 13px; color: #4a5568;
}
.nav-item i { color: #7b8794; font-size: 14px; }
.nav-item:hover { background: #f5f7fb; }
.nav-item.active { background: #eef2ff; color: #3b66f5; font-weight: 600; }
.nav-item.active i { color: #3b66f5; }
.side-help {
  margin-top: 10px; background: #f4f7ff; border: 1px solid #e3eaff;
  border-radius: 12px; padding: 14px; text-align: center;
}
.side-help-img { width: 44px; height: 44px; }
.side-help-title { font-weight: 700; font-size: 13px; margin-top: 4px; }
.side-help-sub { color: #8492a6; font-size: 12px; margin: 2px 0 10px; }

/* ============ 主内容 ============ */
.main { flex: 1; min-width: 0; overflow-y: auto; padding: 18px 28px 48px; }
.topbar {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 18px; gap: 12px;
}
.topbar-left { display: inline-flex; align-items: center; gap: 4px; font-size: 15px; }
.back { padding: 0; }
.topbar-right { display: inline-flex; align-items: center; gap: 10px; }
.top-search { width: 240px; }

.hero {
  display: flex; align-items: center; justify-content: space-between; gap: 20px;
  background: linear-gradient(115deg, #eef2ff 0%, #f3efff 60%, #eef7ff 100%);
  border: 1px solid #e3eaff; border-radius: 16px;
  padding: 30px 34px; margin-bottom: 26px;
}
.hero h1 { margin: 0 0 8px; font-size: 26px; }
.hero p { margin: 0 0 16px; color: #6b7694; font-size: 14px; }
.hero-search { width: 380px; max-width: 100%; }
.hero-img { width: 120px; height: 120px; flex-shrink: 0; filter: drop-shadow(0 12px 24px rgba(88,101,242,.25)); }

.section-title { font-size: 16px; font-weight: 700; margin: 4px 0 14px; }

.rec-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(210px, 1fr)); gap: 14px; margin-bottom: 28px; }
.rec-card {
  background: #fff; border: 1px solid #eef1f6; border-radius: 12px;
  padding: 16px; cursor: pointer; transition: box-shadow .15s, transform .15s;
}
.rec-card:hover { box-shadow: 0 8px 20px rgba(76,93,210,.1); transform: translateY(-2px); }
.rec-icon {
  width: 36px; height: 36px; border-radius: 10px; background: #eef2ff;
  display: flex; align-items: center; justify-content: center; margin-bottom: 10px;
}
.rec-icon i { font-size: 18px; color: #4c7dff; }
.rec-title { font-weight: 700; font-size: 14px; margin-bottom: 6px; }
.rec-desc { color: #8492a6; font-size: 12px; line-height: 1.6; min-height: 38px; }
.rec-foot { display: flex; justify-content: space-between; align-items: center; margin-top: 10px; }
.rec-min { color: #a0aec0; font-size: 12px; }

.cat-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 14px; margin-bottom: 28px; }
.cat-card {
  display: flex; gap: 12px; background: #fff; border: 1px solid #eef1f6;
  border-radius: 12px; padding: 16px; cursor: pointer; transition: box-shadow .15s;
}
.cat-card:hover { box-shadow: 0 8px 20px rgba(76,93,210,.1); }
.cat-icon {
  width: 40px; height: 40px; border-radius: 10px; background: #eef2ff; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
}
.cat-icon i { font-size: 19px; color: #4c7dff; }
.cat-name { font-weight: 700; font-size: 14px; }
.cat-desc { color: #8492a6; font-size: 12px; margin: 3px 0; }
.cat-count { color: #a0aec0; font-size: 12px; }

.upd-list { background: #fff; border: 1px solid #eef1f6; border-radius: 12px; overflow: hidden; }
.upd-row {
  display: flex; align-items: center; gap: 12px; padding: 13px 16px;
  cursor: pointer; border-bottom: 1px solid #f4f6fa;
}
.upd-row:last-child { border-bottom: none; }
.upd-row:hover { background: #fafbff; }
.upd-ico { color: #4c7dff; font-size: 16px; }
.upd-main { flex: 1; min-width: 0; }
.upd-title { font-size: 13px; font-weight: 600; }
.upd-date { color: #a0aec0; font-size: 12px; }
.see-all { text-align: center; color: #4c7dff; font-size: 13px; margin-top: 14px; cursor: pointer; }

/* ---- 文章 ---- */
.crumb { color: #a0aec0; font-size: 12px; display: flex; align-items: center; gap: 6px; margin-bottom: 14px; }
.crumb-link { cursor: pointer; }
.crumb-link:hover { color: #4c7dff; }
.doc-title { margin: 0 0 8px; font-size: 24px; }
.doc-meta { color: #a0aec0; font-size: 12px; display: flex; gap: 16px; margin-bottom: 20px; }
.doc-section { margin-bottom: 8px; }
.doc-section h2 {
  font-size: 17px; margin: 22px 0 10px; padding-left: 10px;
  border-left: 3px solid #4c7dff;
}
.doc-html { font-size: 14px; line-height: 1.9; color: #374151; }
.doc-html >>> ul, .doc-html >>> ol { padding-left: 22px; margin: 8px 0; }
.doc-html >>> li { margin: 4px 0; }
.doc-html >>> p { margin: 8px 0; }
.doc-html >>> code {
  background: #f0f2f5; padding: 1px 6px; border-radius: 4px; font-size: 12.5px;
}
.doc-foot {
  margin-top: 34px; padding: 14px 16px; background: #fff; border: 1px solid #eef1f6;
  border-radius: 12px; display: flex; align-items: center; gap: 12px;
  color: #8492a6; font-size: 13px;
}

/* ---- 搜索 ---- */
.result-row {
  background: #fff; border: 1px solid #eef1f6; border-radius: 10px;
  padding: 12px 16px; margin-bottom: 10px; cursor: pointer;
}
.result-row:hover { box-shadow: 0 6px 16px rgba(76,93,210,.08); }
.result-title { font-size: 14px; font-weight: 600; margin-bottom: 4px; }
.result-title i { color: #4c7dff; }
.result-snippet { color: #8492a6; font-size: 12px; }
.empty { color: #a0aec0; text-align: center; padding: 40px 0; }

/* ============ 右侧目录 ============ */
.toc {
  width: 200px; flex-shrink: 0; padding: 22px 18px; overflow-y: auto;
  border-left: 1px solid #eef1f6; background: #fff;
}
.toc-title { font-weight: 700; font-size: 13px; margin-bottom: 12px; }
.toc-h1 { display: block; color: #3b66f5; font-size: 13px; font-weight: 600; margin: 12px 0 6px; }
.toc-item {
  display: block; color: #6b7694; font-size: 12.5px; padding: 4px 0 4px 12px;
  cursor: pointer; border-left: 2px solid #eef1f6;
}
.toc-item:hover { color: #4c7dff; }
.toc-item.active { color: #4c7dff; border-left-color: #4c7dff; }

.fb-tip { margin: 0 0 12px; color: #8492a6; font-size: 13px; }

@media (max-width: 1100px) {
  .toc { display: none; }
}
@media (max-width: 860px) {
  .side { display: none; }
  .hero-img { display: none; }
}
</style>
