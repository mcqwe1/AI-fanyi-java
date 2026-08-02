<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="logo" @click="$router.push('/')">
        <span class="logo-icon"><i class="el-icon-caret-right" /></span>
        <span class="logo-text">AI视频翻译</span>
      </div>

      <nav class="nav">
        <template v-for="group in groups">
          <div v-if="group.title" :key="group.title" class="group-title">{{ group.title }}</div>
          <a v-for="item in group.items" :key="group.title + item.label"
             class="nav-item" :class="{ active: isActive(item) }"
             @click="go(item)">
            <i :class="item.icon" />
            <span>{{ item.label }}</span>
          </a>
        </template>
      </nav>
    </aside>

    <div class="main">
      <div class="main-topbar">
        <span />
        <span class="top-actions">
          <el-badge is-dot class="bell" :hidden="true">
            <i class="el-icon-bell top-icon" @click="soon" />
          </el-badge>
          <el-dropdown trigger="click" @command="onUserCmd">
            <span class="user-chip"><i class="el-icon-user-solid" /> {{ username }}</span>
            <el-dropdown-menu slot="dropdown">
              <el-dropdown-item command="settings" icon="el-icon-setting">设置</el-dropdown-item>
              <el-dropdown-item command="guide" icon="el-icon-question">使用教程</el-dropdown-item>
              <el-dropdown-item command="logout" icon="el-icon-switch-button" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </el-dropdown>
        </span>
      </div>
      <div class="content">
        <router-view />
      </div>
    </div>

    <!-- 右下角智能客服（全部页面可见） -->
    <SupportChat />
  </div>
</template>

<script>
import SupportChat from '../components/SupportChat.vue'

export default {
  name: 'AppShell',
  components: { SupportChat },
  data () {
    return {
      groups: [
        { title: null, items: [{ icon: 'el-icon-s-home', label: '首页', to: '/' }] },
        {
          title: '翻译功能',
          items: [
            { icon: 'el-icon-video-camera', label: '普通AI视频翻译', to: '/normal' },
            { icon: 'el-icon-notebook-2', label: '术语库AI视频翻译', to: '/kb' },
        { icon: 'el-icon-magic-stick', label: '全能AI翻译', to: '/agent' },
            { icon: 'el-icon-document', label: '文本AI翻译', to: '/text' },
            { icon: 'el-icon-headset', label: '音频AI翻译', to: '/audio' }
          ]
        },
        {
          title: '任务管理',
          items: [
            { icon: 'el-icon-tickets', label: '我的任务', to: '/tasks' }
          ]
        },
        {
          title: '资源管理',
          items: [
            { icon: 'el-icon-collection', label: '术语库', to: '/glossary' }
          ]
        },
        {
          title: '设置',
          items: [
            { icon: 'el-icon-setting', label: '设置', to: '/settings' }
          ]
        }
      ]
    }
  },
  computed: {
    username () { return this.$store.state.username }
  },
  methods: {
    go (item) {
      if (this.$route.fullPath !== item.to) this.$router.push(item.to)
    },
    isActive (item) {
      return this.$route.fullPath === item.to
    },
    soon () {
      this.$message.info('该功能规划中，敬请期待')
    },
    onUserCmd (cmd) {
      if (cmd === 'logout') {
        this.$store.commit('logout')
        this.$router.push('/login')
      } else if (cmd === 'settings') {
        this.$router.push('/settings')
      } else if (cmd === 'guide') {
        this.$router.push('/guide')
      }
    }
  }
}
</script>

<style scoped>
.shell { display: flex; height: 100vh; overflow: hidden; }

.sidebar {
  width: 224px; flex-shrink: 0; background: #fff;
  border-right: 1px solid #eef1f6;
  display: flex; flex-direction: column;
  padding: 18px 14px;
  overflow-y: auto;
}
.logo { display: flex; align-items: center; gap: 8px; cursor: pointer; padding: 2px 6px 16px; }
.logo-icon {
  width: 34px; height: 34px; border-radius: 10px; flex-shrink: 0;
  background: linear-gradient(135deg, #60a5fa, #6366f1);
  color: #fff; font-size: 18px;
  display: inline-flex; align-items: center; justify-content: center;
}
.logo-text { font-weight: 700; font-size: 16px; color: var(--text-main); }

.nav { flex: 1; }
.group-title { color: #a0aec0; font-size: 12px; margin: 14px 8px 6px; }
.nav-item {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 10px; margin: 2px 0; border-radius: 10px;
  color: #4a5568; font-size: 14px; cursor: pointer; user-select: none;
}
.nav-item i { font-size: 16px; color: #7b8794; }
.nav-item:hover { background: #f5f7fb; }
.nav-item.active { background: #eef2ff; color: var(--brand-deep); font-weight: 600; }
.nav-item.active i { color: var(--brand-deep); }

.main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.main-topbar {
  height: 56px; flex-shrink: 0; background: transparent;
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 28px;
}
.top-actions { display: flex; align-items: center; gap: 18px; }
.top-icon { font-size: 18px; color: #7b8794; cursor: pointer; }
.user-chip {
  display: inline-flex; align-items: center; gap: 6px; cursor: pointer;
  background: #fff; border: 1px solid #eef1f6; border-radius: 999px;
  padding: 6px 14px; font-size: 13px; color: var(--text-main);
}
.content { flex: 1; overflow-y: auto; padding: 4px 28px 32px; }
</style>
