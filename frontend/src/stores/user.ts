import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { UserInfo } from '@/api/types'

const TOKEN_KEY = 'stockapp_token'
const USER_KEY = 'stockapp_user'

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) || '')
  const user = ref<UserInfo | null>(
    JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  )

  function setLogin(t: string, u: UserInfo) {
    token.value = t
    user.value = u
    localStorage.setItem(TOKEN_KEY, t)
    localStorage.setItem(USER_KEY, JSON.stringify(u))
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  return { token, user, setLogin, logout, isLoggedIn: () => !!token.value }
})
