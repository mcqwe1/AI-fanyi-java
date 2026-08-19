import Vue from 'vue'
import VueRouter from 'vue-router'
import AppShell from '../layouts/AppShell.vue'
import Login from '../views/Login.vue'
import HomeDashboard from '../views/HomeDashboard.vue'
import NormalMode from '../views/NormalMode.vue'
import AgentMode from '../views/AgentMode.vue'
import GlossaryPage from '../views/GlossaryPage.vue'
import TextMode from '../views/TextMode.vue'
import DocMode from '../views/DocMode.vue'
import DocCompare from '../views/DocCompare.vue'
import TasksPage from '../views/TasksPage.vue'
import Settings from '../views/Settings.vue'
import HelpCenter from '../views/HelpCenter.vue'
import SelectionTranslate from '../views/SelectionTranslate.vue'
import SubtitleEditor from '../views/SubtitleEditor.vue'
import DubEditor from '../views/DubEditor.vue'
import AdminDashboard from '../views/AdminDashboard.vue'

Vue.use(VueRouter)

const routes = [
  { path: '/login', component: Login },
  { path: '/help', component: HelpCenter },
  // 旧书签兼容：/guide 重定向到新帮助中心
  { path: '/guide', redirect: '/help' },
  { path: '/editor/:id', component: SubtitleEditor },
  { path: '/dub/:id', component: DubEditor },
  {
    path: '/',
    component: AppShell,
    children: [
      { path: '', component: HomeDashboard },
      // 2026-08 需求改版：音频/视频合并为一个入口；旧书签 /audio 重定向
      { path: 'normal', component: NormalMode, meta: { kind: 'media' } },
      { path: 'audio', redirect: '/normal' },
      // KB 模式已删（2026-08）：老书签重定向到接管它的全能 AI 翻译
      { path: 'kb', redirect: '/agent' },
      { path: 'agent', component: AgentMode },
      { path: 'glossary', component: GlossaryPage },
      { path: 'text', component: TextMode },
      { path: 'doc', component: DocMode },
      { path: 'doc/:id/compare', component: DocCompare },
      { path: 'selection', component: SelectionTranslate },
      { path: 'tasks', component: TasksPage },
      { path: 'settings', component: Settings },
      { path: 'admin', component: AdminDashboard, meta: { requiresAdmin: true } }
    ]
  }
]

const router = new VueRouter({ routes })
const PUBLIC_PATHS = ['/login', '/help', '/guide']

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  if (!PUBLIC_PATHS.includes(to.path) && !token) return next('/login')
  if (to.matched.some(record => record.meta.requiresAdmin) && localStorage.getItem('role') !== 'ADMIN') {
    return next('/')
  }
  next()
})

export default router
