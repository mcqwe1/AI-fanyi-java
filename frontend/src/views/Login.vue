<template>
  <div class="login-page">
    <!-- 左侧动态品牌区 -->
    <div class="brand-pane">
      <span class="blob blob-1" /><span class="blob blob-2" /><span class="blob blob-3" />

      <!-- 漂浮的多语言文字 -->
      <span v-for="w in words" :key="w.text" class="float-word"
            :style="{ left: w.x, top: w.y, animationDelay: w.delay, fontSize: w.size }">{{ w.text }}</span>

      <!-- 底部流动波纹 -->
      <div class="waves">
        <svg class="wave wave-1" viewBox="0 0 1440 200" preserveAspectRatio="none">
          <path d="M0,120 C240,60 480,180 720,120 C960,60 1200,180 1440,120 L1440,200 L0,200 Z" fill="url(#wg1)" />
          <defs><linearGradient id="wg1" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0" stop-color="#818cf8" stop-opacity=".18" />
            <stop offset="1" stop-color="#60a5fa" stop-opacity=".28" /></linearGradient></defs>
        </svg>
        <svg class="wave wave-2" viewBox="0 0 1440 200" preserveAspectRatio="none">
          <path d="M0,140 C260,90 520,190 780,140 C1040,90 1240,190 1440,140 L1440,200 L0,200 Z" fill="url(#wg2)" />
          <defs><linearGradient id="wg2" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0" stop-color="#a78bfa" stop-opacity=".2" />
            <stop offset="1" stop-color="#6366f1" stop-opacity=".14" /></linearGradient></defs>
        </svg>
        <svg class="wave wave-3" viewBox="0 0 1440 200" preserveAspectRatio="none">
          <path d="M0,160 C300,120 600,195 900,160 C1150,132 1300,190 1440,160 L1440,200 L0,200 Z" fill="url(#wg3)" />
          <defs><linearGradient id="wg3" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0" stop-color="#93c5fd" stop-opacity=".25" />
            <stop offset="1" stop-color="#c4b5fd" stop-opacity=".2" /></linearGradient></defs>
        </svg>
      </div>
    </div>

    <!-- 品牌内容（背景左侧） -->
    <div class="brand-center">
      <img :src="logo" class="brand-logo" alt="狐译" draggable="false">
      <div class="brand-title">狐译<span class="spark">✦</span></div>
      <div class="brand-slogan">让语言，自然而然地流动</div>
    </div>

    <!-- 登录卡（嵌入背景右侧） -->
    <div class="form-card">
      <transition name="form-fade" mode="out-in">
        <div class="form-box" :key="mode">
          <img :src="logo" class="form-logo" alt="" draggable="false">
          <h2 class="form-title">{{ isLogin ? '欢迎回来' : '创建账号' }}</h2>
          <p class="form-sub">{{ isLogin ? '登录狐译' : '注册狐译' }}</p>

          <el-form @submit.native.prevent>
            <el-form-item>
              <el-input v-model="username" placeholder="邮箱 / 用户名" prefix-icon="el-icon-user" />
            </el-form-item>
            <el-form-item>
              <el-input v-model="password" placeholder="密码" prefix-icon="el-icon-lock"
                        show-password @keyup.enter.native="submit" />
            </el-form-item>

            <div v-if="isLogin" class="form-row">
              <el-checkbox v-model="remember">记住我</el-checkbox>
              <span class="forgot">忘记密码?</span>
            </div>

            <el-button class="btn-submit" :loading="loading" @click="submit">
              {{ isLogin ? '登 录' : '注 册' }}
            </el-button>
          </el-form>

          <div class="form-switch">
            <template v-if="isLogin">还没有账号？<el-link type="primary" :underline="false" @click="mode = 'register'">立即注册</el-link></template>
            <template v-else>已有账号？<el-link type="primary" :underline="false" @click="mode = 'login'">直接登录</el-link></template>
          </div>

          <div class="form-guide">
            <el-link type="info" :underline="false" @click="openHelp">
              <i class="el-icon-question" /> 第一次用？看使用教程
            </el-link>
          </div>
        </div>
      </transition>
    </div>
  </div>
</template>

<script>
import http from '../api/http'
import logo from '../assets/logo.png'

const REMEMBER_KEY = 'lingyi.rememberUser'

export default {
  name: 'Login',
  data () {
    return {
      logo,
      mode: 'login',
      username: '',
      password: '',
      remember: false,
      loading: false,
      words: [
        { text: 'Hello', x: '12%', y: '68%', delay: '0s', size: '15px' },
        { text: '你好', x: '30%', y: '76%', delay: '1.2s', size: '17px' },
        { text: 'Bonjour', x: '58%', y: '70%', delay: '2.4s', size: '13px' },
        { text: 'こんにちは', x: '74%', y: '62%', delay: '0.8s', size: '13px' },
        { text: 'Hola', x: '44%', y: '84%', delay: '3.1s', size: '14px' },
        { text: '안녕하세요', x: '18%', y: '85%', delay: '2s', size: '12px' },
        { text: 'Ciao', x: '84%', y: '78%', delay: '1.6s', size: '14px' },
        { text: 'Привет', x: '64%', y: '86%', delay: '3.8s', size: '12px' }
      ]
    }
  },
  computed: {
    isLogin () { return this.mode === 'login' }
  },
  mounted () {
    const saved = localStorage.getItem(REMEMBER_KEY)
    if (saved) { this.username = saved; this.remember = true }
  },
  methods: {
    openHelp () {
      const base = location.href.split('#')[0]
      window.open(base + '#/help', '_blank')
    },
    submit () { this.isLogin ? this.login() : this.register() },
    async login () {
      if (!this.username || !this.password) return this.$message.warning('请输入用户名和密码')
      this.loading = true
      try {
        const r = await http.post('/auth/login', { username: this.username, password: this.password })
        if (this.remember) localStorage.setItem(REMEMBER_KEY, this.username)
        else localStorage.removeItem(REMEMBER_KEY)
        this.save(r.data)
      } catch (e) { /* 拦截器已提示 */ } finally { this.loading = false }
    },
    async register () {
      if (!this.username || !this.password) return this.$message.warning('请输入用户名和密码')
      this.loading = true
      try {
        const r = await http.post('/auth/register', {
          username: this.username, password: this.password, nickname: this.username
        })
        this.save(r.data)
        this.$message.success('注册成功')
      } catch (e) { /* ignore */ } finally { this.loading = false }
    },
    save (d) {
      this.$store.commit('setAuth', { token: d.token, username: d.username, role: d.role, avatar: d.avatar || '' })
      this.$router.push(d.role === 'ADMIN' ? '/admin' : '/')
    }
  }
}
</script>

<style scoped>
.login-page {
  height: 100vh; position: relative; overflow: hidden;
  display: flex; align-items: center;
  background: linear-gradient(135deg, #f6f7ff 0%, #eef1fd 45%, #e9ecfb 100%);
}

/* ---------- 全屏动态背景层 ---------- */
.brand-pane { position: absolute; top: 0; left: 0; right: 0; bottom: 0; overflow: hidden; }

.blob { position: absolute; border-radius: 50%; filter: blur(60px); opacity: .55; }
.blob-1 {
  width: 420px; height: 420px; left: -80px; top: -100px;
  background: radial-gradient(circle, #c7d6ff, transparent 70%);
  animation: drift 14s ease-in-out infinite alternate;
}
.blob-2 {
  width: 360px; height: 360px; right: -60px; top: 30%;
  background: radial-gradient(circle, #ddd0ff, transparent 70%);
  animation: drift 18s ease-in-out infinite alternate-reverse;
}
.blob-3 {
  width: 300px; height: 300px; left: 30%; bottom: -120px;
  background: radial-gradient(circle, #c5e2ff, transparent 70%);
  animation: drift 16s ease-in-out 2s infinite alternate;
}
@keyframes drift {
  from { transform: translate(0, 0) scale(1); }
  to   { transform: translate(50px, 36px) scale(1.12); }
}

.brand-center { position: relative; z-index: 2; flex: 1; text-align: center; padding-bottom: 60px; }
.brand-logo {
  width: 190px; height: 190px; user-select: none;
  filter: drop-shadow(0 22px 44px rgba(88, 101, 242, .28));
  animation: logo-float 5s ease-in-out infinite;
}
@keyframes logo-float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-14px); }
}
.brand-title {
  margin-top: 22px; font-size: 52px; font-weight: 800; letter-spacing: 6px;
  background: linear-gradient(100deg, #3b82f6, #7c5cff);
  -webkit-background-clip: text; background-clip: text; color: transparent;
}
.spark {
  font-size: 22px; vertical-align: super; margin-left: 2px;
  background: linear-gradient(100deg, #7c5cff, #b38bff);
  -webkit-background-clip: text; background-clip: text; color: transparent;
  animation: spark-blink 2.6s ease-in-out infinite;
}
@keyframes spark-blink { 0%, 100% { opacity: 1; } 50% { opacity: .25; } }
.brand-slogan { margin-top: 14px; color: #6b7694; font-size: 16px; letter-spacing: 3px; }

.float-word {
  position: absolute; z-index: 1; color: #7d8ab5; opacity: 0;
  user-select: none; pointer-events: none;
  animation: word-float 7s ease-in-out infinite;
}
@keyframes word-float {
  0%   { opacity: 0; transform: translateY(14px); }
  25%  { opacity: .65; }
  55%  { opacity: .5; }
  100% { opacity: 0; transform: translateY(-34px); }
}

.waves { position: absolute; left: 0; right: 0; bottom: 0; height: 190px; z-index: 1; }
.wave { position: absolute; bottom: 0; height: 100%; width: 200%; left: 0; }
.wave-1 { animation: wave-move 11s linear infinite; }
.wave-2 { animation: wave-move 17s linear infinite reverse; }
.wave-3 { animation: wave-move 23s linear infinite; }
@keyframes wave-move {
  from { transform: translateX(0); }
  to   { transform: translateX(-50%); }
}

/* ---------- 嵌入背景的登录卡 ---------- */
.form-card {
  position: relative; z-index: 3; width: 400px; flex-shrink: 0;
  margin-right: 7vw; padding: 44px 40px 36px;
  background: rgba(255, 255, 255, .92);
  backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px);
  border: 1px solid rgba(255, 255, 255, .7); border-radius: 22px;
  box-shadow: 0 20px 60px rgba(76, 93, 210, .16);
}
.form-box { width: 100%; }
.form-logo { display: none; width: 72px; height: 72px; margin: 0 auto 10px; }
.form-title { margin: 0; font-size: 26px; color: var(--text-main); }
.form-sub { margin: 8px 0 26px; color: var(--text-sub); font-size: 14px; }

.form-box >>> .el-input__inner { height: 44px; line-height: 44px; border-radius: 10px; }

.form-row {
  display: flex; justify-content: space-between; align-items: center;
  margin: -4px 0 18px; font-size: 13px;
}
.forgot { color: #8492a6; cursor: pointer; }

.btn-submit {
  width: 100%; height: 44px; border: none; border-radius: 10px;
  color: #fff; font-size: 15px; letter-spacing: 2px;
  background: linear-gradient(90deg, #4c7dff, #6a5cff);
  box-shadow: 0 8px 20px rgba(90, 105, 245, .3);
  transition: transform .15s, box-shadow .15s;
}
.btn-submit:hover { transform: translateY(-1px); box-shadow: 0 10px 24px rgba(90, 105, 245, .4); color: #fff; }

.form-switch { text-align: center; margin-top: 22px; font-size: 13px; color: #8492a6; }
.form-switch .el-link { font-size: 13px; vertical-align: baseline; }
.form-guide { text-align: center; margin-top: 34px; }
.form-guide .el-link { font-size: 12px; }

.form-fade-enter-active, .form-fade-leave-active { transition: opacity .18s, transform .18s; }
.form-fade-enter { opacity: 0; transform: translateY(8px); }
.form-fade-leave-to { opacity: 0; transform: translateY(-8px); }

/* 窄屏：隐藏品牌内容，登录卡居中 */
@media (max-width: 860px) {
  .brand-center { display: none; }
  .form-card { margin: 0 auto; width: min(400px, calc(100vw - 32px)); }
  .form-logo { display: block; }
}
</style>
