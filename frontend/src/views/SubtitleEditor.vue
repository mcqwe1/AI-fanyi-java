<template>
  <div class="editor">
    <!-- 顶栏 -->
    <div class="ed-top">
      <span class="ed-left">
        <el-button type="text" icon="el-icon-back" class="back" @click="goBack">返回</el-button>
        <span class="title">字幕编辑</span>
        <span v-if="task" class="fname">{{ task.originalFilename }}（#{{ task.id }}）</span>
        <span class="count">{{ subs.length }} 行</span>
      </span>
      <span class="ed-right">
        <el-radio-group v-model="displayMode" size="mini">
          <el-radio-button label="tgt">译文</el-radio-button>
          <el-radio-button label="src">原文</el-radio-button>
          <el-radio-button label="both">双语</el-radio-button>
        </el-radio-group>
        <el-switch v-model="follow" active-text="跟随" class="follow" />
        <el-button size="small" @click="downloadSrt">下载 SRT</el-button>
        <el-button size="small" type="primary" :loading="saving" :disabled="!dirty" @click="save">
          {{ dirty ? '保存' : '已保存' }}
        </el-button>
      </span>
    </div>

    <div v-loading="!loaded" class="ed-main">
      <!-- 左:播放器 + 当前行详情编辑 -->
      <div class="left-pane">
        <div class="video-box" :class="{ 'audio-mode': isAudio || audioFallback }">
          <video ref="player" :src="mediaUrl" controls preload="metadata"
                 @timeupdate="onTime" @seeked="onTime" @play="playing = true" @pause="playing = false"
                 @error="onMediaError" />
          <div v-if="overlayText" class="overlay" v-html="overlayText" />
          <div v-if="audioFallback && !isAudio" class="fallback-note">
            <i class="el-icon-headset" /> 此视频容器浏览器无法直接播放，已切换到音频轨对轴
          </div>
        </div>

        <div class="player-tools">
          <el-button size="mini" icon="el-icon-d-arrow-left" @click="jump(-1)">上一句</el-button>
          <el-button size="mini" :icon="playing ? 'el-icon-video-pause' : 'el-icon-video-play'"
                     @click="togglePlay">{{ playing ? '暂停' : '播放' }}</el-button>
          <el-button size="mini" @click="jump(1)">下一句 <i class="el-icon-d-arrow-right" /></el-button>
          <span class="clock">{{ fmt(currentMs) }}</span>
        </div>

        <!-- 当前选中行的编辑区(唯一一套重组件,不随字幕行数增长) -->
        <el-card v-if="cur" class="edit-card" shadow="never">
          <div class="edit-head">正在编辑第 <b>{{ curIdx + 1 }}</b> 行</div>
          <div class="time-row">
            <span class="t-lab">起</span>
            <el-input size="small" class="t-input" v-model="curStartText" @change="commitTime('startMs', curStartText)" />
            <el-button size="small" @click="nudge('startMs', -100)">-0.1s</el-button>
            <el-button size="small" @click="nudge('startMs', 100)">+0.1s</el-button>
            <el-button size="small" type="primary" plain @click="stamp('startMs')">◎ 打点</el-button>
          </div>
          <div class="time-row">
            <span class="t-lab">止</span>
            <el-input size="small" class="t-input" v-model="curEndText" @change="commitTime('endMs', curEndText)" />
            <el-button size="small" @click="nudge('endMs', -100)">-0.1s</el-button>
            <el-button size="small" @click="nudge('endMs', 100)">+0.1s</el-button>
            <el-button size="small" type="primary" plain @click="stamp('endMs')">◎ 打点</el-button>
            <span class="dur-tip" :class="{ bad: cur.endMs <= cur.startMs }">
              时长 {{ ((cur.endMs - cur.startMs) / 1000).toFixed(1) }}s
            </span>
          </div>
          <el-input v-model="cur.sourceText" type="textarea" :autosize="{ minRows: 1, maxRows: 3 }"
                    placeholder="原文" class="mb" @input="touch" />
          <el-input v-model="cur.targetText" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }"
                    placeholder="译文" class="tgt" @input="touch" />
          <div class="edit-ops">
            <el-button size="mini" icon="el-icon-plus" @click="insertAfter(curIdx)">下方插行</el-button>
            <el-button size="mini" class="del" icon="el-icon-delete" @click="removeRow(curIdx)">删除本行</el-button>
            <span class="hint">空格 播放/暂停 · Ctrl+S 保存 · ↑↓ 切换行</span>
          </div>
        </el-card>
        <div v-else class="edit-empty">点击右侧任意一行开始编辑</div>
      </div>

      <!-- 右:轻量字幕列表(纯文本,不含输入框,几千行也不卡) -->
      <div ref="list" class="list-pane">
        <div v-for="(l, i) in subs" :key="l._key" :ref="'row' + i"
             class="sub-row" :class="{ cur: i === curIdx, playing: i === currentIdx, bad: l.endMs <= l.startMs }"
             @click="select(i, true)">
          <div class="row-l">
            <span class="seq">{{ i + 1 }}</span>
            <span class="time">{{ fmt(l.startMs) }}</span>
          </div>
          <div class="row-txt">
            <div v-if="displayMode !== 'src'" class="tline">{{ l.targetText || '（空）' }}</div>
            <div v-if="displayMode !== 'tgt'" class="sline">{{ l.sourceText }}</div>
          </div>
        </div>
        <el-empty v-if="loaded && !subs.length" description="该任务还没有字幕" :image-size="80" />
      </div>
    </div>
  </div>
</template>

<script>
import http from '../api/http'
import { blobDown } from '../utils/download'

let keySeq = 1

export default {
  name: 'SubtitleEditor',
  data () {
    return {
      task: null,
      subs: [],
      loaded: false,
      dirty: false,
      saving: false,
      displayMode: 'both',
      follow: true,
      currentMs: 0,
      currentIdx: -1,     // 正在播放的行
      curIdx: -1,         // 正在编辑(选中)的行
      curStartText: '',
      curEndText: '',
      playing: false,
      audioFallback: false
    }
  },
  computed: {
    taskId () { return this.$route.params.id },
    isAudio () { return this.task && this.task.mediaType === 'AUDIO' },
    mediaUrl () {
      const base = `/api/tasks/${this.taskId}/media?access_token=${encodeURIComponent(localStorage.getItem('token') || '')}`
      return this.audioFallback ? base + '&track=audio' : base
    },
    cur () { return this.subs[this.curIdx] || null },
    overlayText () {
      const l = this.subs[this.currentIdx]
      if (!l) return ''
      const esc = t => (t || '').replace(/&/g, '&amp;').replace(/</g, '&lt;')
      if (this.displayMode === 'src') return esc(l.sourceText)
      if (this.displayMode === 'tgt') return esc(l.targetText)
      const tgt = esc(l.targetText)
      const src = esc(l.sourceText)
      return tgt && src ? `${tgt}<br><span class="ov-src">${src}</span>` : (tgt || src)
    }
  },
  async mounted () {
    try {
      const [t, s] = await Promise.all([
        http.get(`/tasks/${this.taskId}`),
        http.get(`/tasks/${this.taskId}/subtitles`)
      ])
      this.task = t.data
      this.subs = (s.data || []).map(x => ({
        _key: keySeq++,
        startMs: x.startMs, endMs: x.endMs,
        sourceText: x.sourceText || '', targetText: x.targetText || ''
      }))
      if (this.subs.length) this.select(0, false)
    } catch (e) { /* 拦截器已提示 */ } finally {
      this.loaded = true
    }
    window.addEventListener('keydown', this.onKey)
    window.addEventListener('beforeunload', this.onUnload)
  },
  beforeDestroy () {
    window.removeEventListener('keydown', this.onKey)
    window.removeEventListener('beforeunload', this.onUnload)
  },
  beforeRouteLeave (to, from, next) {
    if (!this.dirty) return next()
    this.$confirm('有未保存的修改，确定离开吗？', '提示', {
      confirmButtonText: '不保存并离开', cancelButtonText: '留在本页', type: 'warning'
    }).then(() => next()).catch(() => next(false))
  },
  methods: {
    touch () { if (this.loaded) this.dirty = true },
    /** 选中某行进入编辑；seek=true 时同时跳播放器 */
    select (i, seek) {
      if (i < 0 || i >= this.subs.length) return
      this.curIdx = i
      const l = this.subs[i]
      this.curStartText = this.fmt(l.startMs)
      this.curEndText = this.fmt(l.endMs)
      if (seek) this.seekTo(l.startMs)
    },
    /** 毫秒 → mm:ss.mmm（超 1 小时带小时段） */
    fmt (ms) {
      if (ms == null || ms < 0) ms = 0
      const h = Math.floor(ms / 3600000)
      const m = Math.floor(ms % 3600000 / 60000)
      const s = Math.floor(ms % 60000 / 1000)
      const mi = Math.floor(ms % 1000)
      const mm = String(m).padStart(2, '0')
      const ss = String(s).padStart(2, '0')
      const mmm = String(mi).padStart(3, '0')
      return h > 0 ? `${h}:${mm}:${ss}.${mmm}` : `${mm}:${ss}.${mmm}`
    },
    /** 解析 "mm:ss.mmm" / "h:mm:ss,mmm" / 纯秒数；非法返回 null */
    parse (text) {
      if (!text && text !== 0) return null
      const t = String(text).trim().replace('，', '.').replace(',', '.')
      const m = t.match(/^(?:(\d+):)?(?:(\d{1,2}):)?(\d{1,2})(?:\.(\d{1,3}))?$/)
      if (!m) return null
      const nums = [m[1], m[2], m[3]].filter(x => x !== undefined).map(Number)
      while (nums.length < 3) nums.unshift(0)
      const [h, mi, s] = nums
      const ms = Number((m[4] || '0').padEnd(3, '0'))
      return ((h * 60 + mi) * 60 + s) * 1000 + ms
    },
    commitTime (field, text) {
      const v = this.parse(text)
      if (v == null) {
        this.$message.warning('时间格式形如 01:23.450')
        this.select(this.curIdx, false)   // 复位输入框
        return
      }
      this.cur[field] = v
      this.syncText()
      this.touch()
    },
    nudge (field, delta) {
      if (!this.cur) return
      let v = this.cur[field] + delta
      if (v < 0) v = 0
      if (field === 'endMs' && v <= this.cur.startMs) v = this.cur.startMs + 100
      this.cur[field] = v
      this.syncText()
      this.touch()
    },
    stamp (field) {
      if (!this.cur) return
      let v = this.currentMs
      if (field === 'endMs' && v <= this.cur.startMs) v = this.cur.startMs + 100
      this.cur[field] = v
      this.syncText()
      this.touch()
    },
    syncText () {
      if (!this.cur) return
      this.curStartText = this.fmt(this.cur.startMs)
      this.curEndText = this.fmt(this.cur.endMs)
    },
    /** 视频容器放不了(mkv/avi/wmv…) → 自动降级为流水线抽出的音频轨继续对轴 */
    onMediaError () {
      if (this.audioFallback || this.isAudio) return
      this.audioFallback = true
      this.$message.info('视频格式浏览器不支持直接播放，已切换为音频对轴模式')
    },
    onTime () {
      const p = this.$refs.player
      if (!p) return
      this.currentMs = Math.round(p.currentTime * 1000)
      const idx = this.subs.findIndex(l => this.currentMs >= l.startMs && this.currentMs < l.endMs)
      if (idx !== this.currentIdx) {
        this.currentIdx = idx
        if (idx >= 0 && this.follow) this.scrollToRow(idx)
      }
    },
    scrollToRow (idx) {
      this.$nextTick(() => {
        const row = (this.$refs['row' + idx] || [])[0]
        if (row) row.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
      })
    },
    seekTo (ms) {
      const p = this.$refs.player
      if (p) p.currentTime = ms / 1000
    },
    togglePlay () {
      const p = this.$refs.player
      if (!p) return
      p.paused ? p.play() : p.pause()
    },
    /** 跳上一句/下一句并选中 */
    jump (dir) {
      if (!this.subs.length) return
      let i = this.curIdx >= 0 ? this.curIdx : this.currentIdx
      if (i < 0) i = 0
      else i = Math.min(this.subs.length - 1, Math.max(0, i + dir))
      this.select(i, true)
      this.scrollToRow(i)
    },
    insertAfter (i) {
      const cur = this.subs[i]
      const next = this.subs[i + 1]
      const start = cur ? cur.endMs + 10 : this.currentMs
      let end = next ? Math.min(start + 1500, next.startMs - 10) : start + 1500
      if (end <= start) end = start + 500
      this.subs.splice(i + 1, 0, {
        _key: keySeq++, startMs: start, endMs: end, sourceText: '', targetText: ''
      })
      this.touch()
      this.select(i + 1, false)
    },
    removeRow (i) {
      this.subs.splice(i, 1)
      this.touch()
      if (!this.subs.length) { this.curIdx = -1; return }
      this.select(Math.min(i, this.subs.length - 1), false)
    },
    async save () {
      if (!this.subs.length) return this.$message.warning('至少保留一行字幕')
      this.saving = true
      try {
        await http.put(`/tasks/${this.taskId}/subtitles`, {
          subtitles: this.subs.map(l => ({
            startMs: Math.round(l.startMs), endMs: Math.round(l.endMs),
            sourceText: l.sourceText, targetText: l.targetText
          }))
        })
        const s = await http.get(`/tasks/${this.taskId}/subtitles`)
        const keepIdx = this.curIdx
        this.subs = (s.data || []).map(x => ({
          _key: keySeq++,
          startMs: x.startMs, endMs: x.endMs,
          sourceText: x.sourceText || '', targetText: x.targetText || ''
        }))
        this.dirty = false
        if (this.subs.length) this.select(Math.min(keepIdx < 0 ? 0 : keepIdx, this.subs.length - 1), false)
        this.$message.success('已保存，SRT 已同步更新')
      } catch (e) { /* 拦截器已提示 */ } finally { this.saving = false }
    },
    downloadSrt () {
      blobDown(`/tasks/${this.taskId}/srt`, `subtitle-${this.taskId}.srt`)
    },
    goBack () { this.$router.push('/tasks') },
    onKey (e) {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault()
        if (this.dirty) this.save()
        return
      }
      const tag = (e.target.tagName || '').toLowerCase()
      if (tag === 'input' || tag === 'textarea') return
      if (e.code === 'Space') { e.preventDefault(); this.togglePlay() }
      else if (e.code === 'ArrowDown') { e.preventDefault(); this.jump(1) }
      else if (e.code === 'ArrowUp') { e.preventDefault(); this.jump(-1) }
    },
    onUnload (e) {
      if (this.dirty) { e.preventDefault(); e.returnValue = '' }
    }
  }
}
</script>

<style scoped>
.editor { height: 100vh; display: flex; flex-direction: column; background: var(--bg); }

.ed-top {
  height: 54px; flex-shrink: 0; background: #fff; border-bottom: 1px solid #eef1f6;
  display: flex; align-items: center; justify-content: space-between; padding: 0 18px;
}
.ed-left { display: flex; align-items: center; gap: 12px; min-width: 0; }
.title { font-weight: 700; color: var(--text-main); }
.fname { color: var(--text-sub); font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 320px; }
.count { color: #a0aec0; font-size: 12px; }
.ed-right { display: flex; align-items: center; gap: 12px; }
.follow { margin: 0 2px; }

.ed-main { flex: 1; display: flex; min-height: 0; padding: 14px; gap: 14px; }

.left-pane { width: 46%; display: flex; flex-direction: column; gap: 10px; min-width: 380px; overflow-y: auto; }
.video-box { position: relative; background: #000; border-radius: 12px; overflow: hidden; flex-shrink: 0; }
.video-box video { width: 100%; max-height: 42vh; display: block; }
.video-box.audio-mode video { height: 54px; }
.video-box.audio-mode .overlay { display: none; }
.fallback-note {
  position: absolute; top: 8px; left: 8px; right: 8px;
  color: #ffd04b; font-size: 12px; text-shadow: 0 1px 2px #000;
}
.overlay {
  position: absolute; left: 4%; right: 4%; bottom: 12%;
  text-align: center; color: #fff; font-size: 19px; line-height: 1.5;
  text-shadow: 0 0 4px #000, 0 2px 6px #000; pointer-events: none;
}
.overlay >>> .ov-src { font-size: 14px; opacity: .85; }
.player-tools { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.clock { margin-left: auto; font-family: Consolas, monospace; color: var(--text-main); font-size: 15px; }

.edit-card { border: 1px solid #e8ebf2 !important; box-shadow: none !important; }
.edit-head { font-size: 13px; color: var(--text-sub); margin-bottom: 10px; }
.edit-head b { color: var(--brand-deep); }
.time-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.t-lab { color: var(--text-sub); font-size: 13px; }
.t-input { width: 118px; }
.t-input >>> input { font-family: Consolas, monospace; }
.dur-tip { color: var(--text-sub); font-size: 12px; margin-left: 4px; }
.dur-tip.bad { color: #f56c6c; }
.mb { margin-bottom: 8px; }
.tgt >>> textarea { color: #1a56db; }
.edit-ops { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.edit-ops .hint { margin-left: auto; }
.edit-empty { color: var(--text-sub); text-align: center; padding: 30px; }

.list-pane { flex: 1; overflow-y: auto; min-width: 0; padding-right: 4px; }
.sub-row {
  display: flex; gap: 10px; align-items: flex-start;
  background: #fff; border: 1px solid #eef1f6; border-left: 3px solid transparent;
  border-radius: 8px; padding: 8px 12px; margin-bottom: 6px; cursor: pointer;
}
.sub-row:hover { background: #f9fafe; }
.sub-row.cur { border-color: #dbe4ff; border-left-color: var(--brand); background: #f5f8ff; }
.sub-row.playing { border-left-color: #10b981; }
.sub-row.bad { background: #fff5f5; }
.row-l { display: flex; flex-direction: column; align-items: flex-end; width: 62px; flex-shrink: 0; }
.seq { color: #b0b8c4; font-size: 12px; }
.time { font-family: Consolas, monospace; font-size: 12px; color: var(--text-sub); }
.row-txt { min-width: 0; flex: 1; }
.tline { color: #1f2d3d; font-size: 14px; line-height: 1.5; word-break: break-word; }
.sline { color: #90a0b0; font-size: 12px; line-height: 1.4; margin-top: 2px; word-break: break-word; }
</style>
