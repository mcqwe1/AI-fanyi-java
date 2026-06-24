import Vue from 'vue'
import Vuex from 'vuex'

Vue.use(Vuex)

export default new Vuex.Store({
  state: {
    token: localStorage.getItem('token') || '',
    username: localStorage.getItem('username') || ''
  },
  mutations: {
    setAuth (state, { token, username }) {
      state.token = token
      state.username = username
      localStorage.setItem('token', token)
      localStorage.setItem('username', username)
    },
    logout (state) {
      state.token = ''
      state.username = ''
      localStorage.removeItem('token')
      localStorage.removeItem('username')
    }
  }
})
