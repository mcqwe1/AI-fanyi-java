import Vue from 'vue'
import Vuex from 'vuex'

Vue.use(Vuex)

export default new Vuex.Store({
  state: {
    token: localStorage.getItem('token') || '',
    username: localStorage.getItem('username') || '',
    role: localStorage.getItem('role') || 'USER',
    avatar: localStorage.getItem('avatar') || ''
  },
  getters: {
    isAdmin: state => state.role === 'ADMIN'
  },
  mutations: {
    setAuth (state, { token, username, role, avatar }) {
      state.token = token
      state.username = username
      state.role = role || state.role || 'USER'
      localStorage.setItem('token', token)
      localStorage.setItem('username', username)
      localStorage.setItem('role', state.role)
      if (avatar !== undefined) {
        state.avatar = avatar || ''
        if (avatar) localStorage.setItem('avatar', avatar)
        else localStorage.removeItem('avatar')
      }
    },
    setAvatar (state, avatar) {
      state.avatar = avatar || ''
      if (avatar) localStorage.setItem('avatar', avatar)
      else localStorage.removeItem('avatar')
    },
    logout (state) {
      state.token = ''
      state.username = ''
      state.role = 'USER'
      state.avatar = ''
      localStorage.removeItem('token')
      localStorage.removeItem('username')
      localStorage.removeItem('role')
      localStorage.removeItem('avatar')
    }
  }
})
