<template>
  <div class="support-root">
    <!-- 聊天面板 -->
    <transition name="support-pop">
      <div v-show="open" class="panel">
        <div class="head">
          <div>
            <div class="title"><i class="el-icon-service" /> 智能客服</div>
            <div class="sub">基于使用手册回答，有问题随便问</div>
          </div>
          <i class="el-icon-minus close" @click="open = false" />
        </div>

        <div ref="list" class="msgs">
          <div v-for="(m, i) in msgs" :key="i" class="msg" :class="m.role">
            <div class="bubble">
              <span class="content">{{ m.content }}</span>
              <div v-if="m.sources && m.sources.length" class="sources">
                📖 {{ m.sources.join('、') }}
              </div>
            </div>
          </div>
          <div v-if="loading" class="msg assistant">
            <div class="bubble typing"><i>·</i><i>·</i><i>·</i></div>
          </div>
          <!-- 首次打开：常见问题一键提问 -->
          <div v-if="msgs.length <= 1 && !loading" class="quick">
            <el-tag v-for="q in quickQuestions" :key="q" size="small" effect="plain"
                    class="quick-tag" @click="send(q)">{{ q }}</el-tag>
          </div>
        </div>

        <div class="input-row">
          <el-input v-model="draft" size="small" placeholder="输入问题，回车发送" :disabled="loading"
                    @keyup.enter.native="send()" />
          <el-button type="primary" size="small" icon="el-icon-s-promotion"
                     :disabled="loading || !draft.trim()" @click="send()" />
        </div>
      </div>
    </transition>

    <!-- 右下角悬浮球 -->
    <div class="ball" :class="{ on: open }" title="智能客服" @click="open = !open">
      <i :class="open ? 'el-icon-arrow-down' : 'el-icon-service'" />
    </div>
  </div>
</template>

<script>
import http from '../api/http'

const WELCOME = {
  role: 'assistant',
  content: '你好，我是智能客服，熟读本软件的使用手册。密钥怎么配、模式怎么选、字幕怎么导出……随便问。'
}

export default {
  name: 'SupportChat',
  data () {
    return {
      open: false,
      draft: '',
      loading: false,
      msgs: [WELCOME],
      quickQuestions: [
        '怎么配置 API 密钥？',
        '五种翻译模式有什么区别？',
        '全能AI翻译怎么用？',
        '译文里有几行没翻译怎么办？'
      ]
    }
  },
  methods: {
    async send (text) {
      const q = (text || this.draft).trim()
      if (!q || this.loading) return
      // 历史 = 当前问题之前的对话（欢迎语无害，一并带上）
      const history = this.msgs.map(m => ({ role: m.role, content: m.content }))
      this.msgs.push({ role: 'user', content: q })
      this.draft = ''
      this.loading = true
      this.scrollBottom()
      try {
        const r = await http.post('/support/chat', { question: q, history })
        const d = r.data || {}
        this.msgs.push({ role: 'assistant', content: d.answer || '（没有得到回答）', sources: d.sources || [] })
      } catch (e) {
        this.msgs.push({ role: 'assistant', content: '客服暂时不可用，请稍后再试。' })
      } finally {
        this.loading = false
        this.scrollBottom()
      }
    },
    scrollBottom () {
      this.$nextTick(() => {
        const el = this.$refs.list
        if (el) el.scrollTop = el.scrollHeight
      })
    }
  }
}
</script>

<style scoped>
/* ── 悬浮球 ── */
.ball {
  position: fixed; right: 26px; bottom: 26px; z-index: 1500;
  width: 54px; height: 54px; border-radius: 50%;
  background: linear-gradient(135deg, #60a5fa, #6366f1);
  color: #fff; font-size: 24px;
  display: flex; align-items: center; justify-content: center;
  cursor: pointer; user-select: none;
  box-shadow: 0 6px 18px rgba(99, 102, 241, .4);
  transition: transform .2s, box-shadow .2s;
}
.ball:hover { transform: scale(1.08); box-shadow: 0 8px 22px rgba(99, 102, 241, .5); }
.ball.on { transform: rotate(0deg) scale(.96); }

/* ── 面板 ── */
.panel {
  position: fixed; right: 26px; bottom: 94px; z-index: 1500;
  width: 370px; height: 520px; max-height: calc(100vh - 130px);
  background: #fff; border-radius: 14px; overflow: hidden;
  box-shadow: 0 12px 40px rgba(31, 41, 55, .18);
  display: flex; flex-direction: column;
}
.support-pop-enter-active, .support-pop-leave-active { transition: all .22s ease; }
.support-pop-enter, .support-pop-leave-to { opacity: 0; transform: translateY(14px) scale(.97); }

.head {
  padding: 14px 16px; color: #fff; flex: none;
  background: linear-gradient(135deg, #60a5fa, #6366f1);
  display: flex; justify-content: space-between; align-items: flex-start;
}
.title { font-size: 15px; font-weight: 700; }
.sub { font-size: 12px; opacity: .85; margin-top: 3px; }
.close { cursor: pointer; font-size: 16px; padding: 2px; }

.msgs { flex: 1; overflow-y: auto; padding: 14px 12px; background: #f7f8fb; }
.msg { display: flex; margin-bottom: 10px; }
.msg.user { justify-content: flex-end; }
.bubble {
  max-width: 82%; padding: 9px 12px; border-radius: 12px;
  font-size: 13px; line-height: 1.65;
}
.msg.assistant .bubble { background: #fff; color: #303133; border-top-left-radius: 4px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, .06); }
.msg.user .bubble { background: linear-gradient(135deg, #60a5fa, #6366f1); color: #fff;
  border-top-right-radius: 4px; }
.content { white-space: pre-wrap; word-break: break-word; }
.sources { color: #909399; font-size: 11px; margin-top: 6px; border-top: 1px dashed #ebeef5; padding-top: 5px; }

.typing i { font-style: normal; font-size: 18px; animation: supportBlink 1.4s infinite both; }
.typing i:nth-child(2) { animation-delay: .2s; }
.typing i:nth-child(3) { animation-delay: .4s; }
@keyframes supportBlink { 0%, 80%, 100% { opacity: .2; } 40% { opacity: 1; } }

.quick { display: flex; flex-wrap: wrap; gap: 8px; padding: 4px 2px; }
.quick-tag { cursor: pointer; }

.input-row {
  flex: none; display: flex; gap: 8px; padding: 10px 12px;
  background: #fff; border-top: 1px solid #f0f2f5;
}
.input-row .el-input { flex: 1; }
</style>
