import Vue from 'vue'
import VueRouter from 'vue-router'
import AppShell from '../layouts/AppShell.vue'
import Login from '../views/Login.vue'
import HomeDashboard from '../views/HomeDashboard.vue'
import NormalMode from '../views/NormalMode.vue'
import KbMode from '../views/KbMode.vue'
import AgentMode from '../views/AgentMode.vue'
import GlossaryPage from '../views/GlossaryPage.vue'
import TextMode from '../views/TextMode.vue'
import TasksPage from '../views/TasksPage.vue'
import Settings from '../views/Settings.vue'
import Guide from '../views/Guide.vue'
import SubtitleEditor from '../views/SubtitleEditor.vue'
import DubEditor from '../views/DubEditor.vue'

Vue.use(VueRouter)

const routes = [
  { path: '/login', component: Login },
  { path: '/guide', component: Guide },
  // 编辑器要最大化空间,不进侧栏外壳
  { path: '/editor/:id', component: SubtitleEditor },
  { path: '/dub/:id', component: DubEditor },
  {
    path: '/',
    component: AppShell,
    children: [
      { path: '', component: HomeDashboard },
      { path: 'normal', component: NormalMode, meta: { kind: 'video' } },
      { path: 'audio', component: NormalMode, meta: { kind: 'audio' } },
      { path: 'kb', component: KbMode },
      { path: 'agent', component: AgentMode },
      { path: 'glossary', component: GlossaryPage },
      { path: 'text', component: TextMode },
      { path: 'tasks', component: TasksPage },
      { path: 'settings', component: Settings }
    ]
  }
]

const router = new VueRouter({ routes })

// 教程页免登录（登录前也能看怎么拿 key）
const PUBLIC_PATHS = ['/login', '/guide']

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  if (!PUBLIC_PATHS.includes(to.path) && !token) {
    next('/login')
  } else {
    next()
  }
})

export default router
