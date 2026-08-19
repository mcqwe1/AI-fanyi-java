<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="logo" @click="$router.push('/')">
        <img :src="logo" class="logo-img" alt="狐译" draggable="false">
        <span class="logo-meta">
          <span class="logo-text">狐译</span>
          <span class="logo-sub">让语言自然流动</span>
        </span>
      </div>

      <el-button class="btn-new" @click="$router.push('/normal')">
        <i class="el-icon-plus" /> 新建翻译
      </el-button>

      <nav class="nav">
        <template v-for="group in groups">
          <div v-if="group.title && visibleItems(group).length" :key="group.title" class="group-title">{{ group.title }}</div>
          <a v-for="item in visibleItems(group)" :key="group.title + item.label"
             class="nav-item" :class="{ active: isActive(item) }"
             @click="go(item)">
            <i :class="item.icon" />
            <span>{{ item.label }}</span>
          </a>
        </template>
      </nav>

      <el-dropdown class="user-card-wrap" trigger="click" placement="top-start" @command="onUserCmd">
        <div class="user-card">
          <img :src="avatar" class="user-avatar" alt="" draggable="false">
          <span class="user-meta">
            <span class="user-name">{{ username }}</span>
            <span class="user-role">{{ isAdmin ? '管理员' : '狐译用户' }}</span>
          </span>
          <i class="el-icon-sort user-caret" />
        </div>
        <el-dropdown-menu slot="dropdown">
          <el-dropdown-item command="settings" icon="el-icon-setting">设置</el-dropdown-item>
          <el-dropdown-item command="guide" icon="el-icon-question">帮助与反馈</el-dropdown-item>
          <el-dropdown-item command="logout" icon="el-icon-switch-button" divided>退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>
    </aside>

    <div class="main">
      <div class="main-topbar">
        <span />
        <span class="top-actions">
          <el-badge is-dot class="bell" :hidden="true">
            <i class="el-icon-bell top-icon" @click="soon" />
          </el-badge>
          <el-dropdown trigger="click" @command="onUserCmd">
            <span class="user-chip"><img :src="avatar" class="chip-avatar" alt=""> {{ username }}</span>
            <el-dropdown-menu slot="dropdown">
              <el-dropdown-item command="settings" icon="el-icon-setting">设置</el-dropdown-item>
              <el-dropdown-item command="guide" icon="el-icon-question">帮助与反馈</el-dropdown-item>
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
import logo from '../assets/logo.png'

export default {
  name: 'AppShell',
  components: { SupportChat },
  data () {
    return {
      logo,
      groups: [
        { title: null, items: [{ icon: 'el-icon-s-home', label: '工作台', to: '/' }] },
        {
          title: '音视频翻译',
          items: [
            { icon: 'el-icon-video-camera', label: '音频/视频翻译', to: '/normal' }
          ]
        },
        {
          title: '文本翻译',
          items: [
            { icon: 'el-icon-document', label: '文本AI翻译', to: '/text' },
            { icon: 'el-icon-folder-opened', label: '文档AI翻译', to: '/doc' }
          ]
        },
        {
          title: 'Agent翻译',
          items: [
            { icon: 'el-icon-magic-stick', label: '全能AI翻译', to: '/agent' }
          ]
        },
        {
          title: '插件',
          items: [
            { icon: 'el-icon-mouse', label: '划词翻译', to: '/selection' }
          ]
        },
        {
          title: '资源',
          items: [
            { icon: 'el-icon-tickets', label: '翻译历史', to: '/tasks' },
            { icon: 'el-icon-collection', label: '术语库', to: '/glossary' }
          ]
        },
        {
          title: '系统',
          items: [
            { icon: 'el-icon-setting', label: '设置', to: '/settings' },
            { icon: 'el-icon-question', label: '帮助与反馈', to: '/guide' },
            { icon: 'el-icon-s-tools', label: '管理员后台', to: '/admin', admin: true }
          ]
        }
      ]
    }
  },
  computed: {
    username () { return this.$store.state.username },
    isAdmin () { return this.$store.getters.isAdmin },
    avatar () { return this.$store.state.avatar || this.logo }
  },
  methods: {
    visibleItems (group) {
      return group.items.filter(item => !item.admin || this.isAdmin)
    },
    openHelp () {
      // 帮助中心在新标签页打开，不覆盖当前工作页面
      const base = location.href.split('#')[0]
      window.open(base + '#/help', '_blank')
    },
    go (item) {
      if (item.to === '/guide') return this.openHelp()
      if (this.$route.fullPath !== item.to) this.$router.push(item.to)
    },
    isActive (item) {
      // 前缀匹配让子路由（如 /doc/5/compare）也点亮父菜单；'/' 需精确匹配
      if (item.to === '/') return this.$route.path === '/'
      return this.$route.path === item.to || this.$route.path.startsWith(item.to + '/')
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
        this.openHelp()
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
  padding: 18px 14px 14px;
  overflow-y: auto;
}
.logo { display: flex; align-items: center; gap: 10px; cursor: pointer; padding: 2px 4px 14px; }
.logo-img { width: 40px; height: 40px; flex-shrink: 0; filter: drop-shadow(0 4px 10px rgba(88, 101, 242, .25)); }
.logo-meta { display: flex; flex-direction: column; line-height: 1.25; }
.logo-text {
  font-weight: 800; font-size: 18px; letter-spacing: 2px;
  background: linear-gradient(100deg, #3b82f6, #7c5cff);
  -webkit-background-clip: text; background-clip: text; color: transparent;
}
.logo-sub { font-size: 10px; color: #a0aec0; letter-spacing: 1px; }

.btn-new {
  border: none; border-radius: 10px; color: #fff; font-size: 14px;
  background: linear-gradient(90deg, #4c7dff, #6a5cff);
  box-shadow: 0 6px 14px rgba(90, 105, 245, .28);
  margin: 0 2px 6px; padding: 11px 0;
}
.btn-new:hover { color: #fff; opacity: .92; }

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

.user-card-wrap { display: block; margin-top: 10px; }
.user-card {
  display: flex; align-items: center; gap: 10px; cursor: pointer;
  border: 1px solid #eef1f6; border-radius: 12px; padding: 10px;
  transition: background .15s;
}
.user-card:hover { background: #f5f7fb; }
.user-avatar { width: 34px; height: 34px; flex-shrink: 0; }
.user-meta { display: flex; flex-direction: column; line-height: 1.3; min-width: 0; flex: 1; }
.user-name { font-size: 13px; font-weight: 600; color: var(--text-main); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.user-role { font-size: 11px; color: #a0aec0; }
.user-caret { color: #a0aec0; font-size: 12px; }

.main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.main-topbar {
  height: 56px; flex-shrink: 0; background: transparent;
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 28px;
}
.top-actions { display: flex; align-items: center; gap: 18px; }
.top-icon { font-size: 18px; color: #7b8794; cursor: pointer; }
.user-chip {
  display: inline-flex; align-items: center; gap: 8px; cursor: pointer;
  background: #fff; border: 1px solid #eef1f6; border-radius: 999px;
  padding: 4px 14px 4px 6px; font-size: 13px; color: var(--text-main);
}
.chip-avatar { width: 26px; height: 26px; }
.content { flex: 1; overflow-y: auto; padding: 4px 28px 32px; }
</style>
