import Vue from 'vue'
import VueRouter from 'vue-router'
import Login from '../views/Login.vue'
import Home from '../views/Home.vue'
import NormalMode from '../views/NormalMode.vue'
import KbMode from '../views/KbMode.vue'
import Settings from '../views/Settings.vue'

Vue.use(VueRouter)

const routes = [
  { path: '/login', component: Login },
  { path: '/', component: Home },
  { path: '/normal', component: NormalMode },
  { path: '/kb', component: KbMode },
  { path: '/settings', component: Settings }
]

const router = new VueRouter({ routes })

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  if (to.path !== '/login' && !token) {
    next('/login')
  } else {
    next()
  }
})

export default router
