<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h2 style="text-align:center;margin-top:0">AI 视频翻译</h2>
      <el-form @submit.native.prevent>
        <el-form-item>
          <el-input v-model="username" placeholder="用户名" prefix-icon="el-icon-user" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="password" type="password" placeholder="密码"
                    prefix-icon="el-icon-lock" @keyup.enter.native="login" />
        </el-form-item>
        <el-button type="primary" :loading="loading" style="width:100%" @click="login">登录</el-button>
        <el-button :loading="loading" style="width:100%;margin:12px 0 0 0" @click="register">注册</el-button>
        <div style="text-align:center;margin-top:14px">
          <el-link type="primary" :underline="false" @click="$router.push('/guide')">
            <i class="el-icon-question" /> 第一次用？看使用教程
          </el-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script>
import http from '../api/http'

export default {
  name: 'Login',
  data () {
    return { username: '', password: '', loading: false }
  },
  methods: {
    async login () {
      if (!this.username || !this.password) return this.$message.warning('请输入用户名和密码')
      this.loading = true
      try {
        const r = await http.post('/auth/login', { username: this.username, password: this.password })
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
      this.$store.commit('setAuth', { token: d.token, username: d.username })
      this.$router.push('/')
    }
  }
}
</script>

<style scoped>
.login-wrap {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #6a82fb 0%, #5b6ee1 100%);
}
.login-card { width: 360px; }
</style>
