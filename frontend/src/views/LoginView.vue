<template>
  <div class="page no-tab auth">
    <button class="back" @click="$router.back()">‹</button>
    <h1 class="h">欢迎回来</h1>
    <p class="sub">登录后可管理自选股</p>

    <input v-model="username" class="input" placeholder="用户名" autocomplete="username" />
    <input v-model="password" type="password" class="input" placeholder="密码"
      autocomplete="current-password" @keyup.enter="onSubmit" />
    <p v-if="error" class="err">{{ error }}</p>
    <button class="btn" :disabled="loading" @click="onSubmit">
      {{ loading ? '登录中…' : '登 录' }}
    </button>
    <p class="foot">还没有账号？
      <router-link to="/register" style="color:var(--accent)">立即注册</router-link>
    </p>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiLogin } from '@/api'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function onSubmit() {
  error.value = ''
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  try {
    const data = await apiLogin(username.value.trim(), password.value)
    userStore.setLogin(data.token, data.user)
    router.replace(String(route.query.redirect || '/'))
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth { padding-top: 24px; }
.back { background: none; border: none; color: var(--text); font-size: 30px; cursor: pointer; padding: 0; }
.h { font-size: 26px; margin: 22px 0 6px; }
.sub { color: var(--text-3); font-size: 13px; margin-bottom: 28px; }
.input { margin-bottom: 12px; }
.btn { margin-top: 8px; }
.err { color: var(--up); font-size: 13px; margin: 2px 2px 8px; }
.foot { text-align: center; color: var(--text-3); font-size: 13px; margin-top: 18px; }
</style>
