<template>
  <div class="page no-tab auth">
    <button class="back" @click="$router.back()">‹</button>
    <h1 class="h">创建账号</h1>
    <p class="sub">用户名 3-32 位，密码至少 6 位</p>

    <input v-model="username" class="input" placeholder="用户名" autocomplete="username" />
    <input v-model="password" type="password" class="input" placeholder="密码（至少 6 位）"
      autocomplete="new-password" />
    <input v-model="confirm" type="password" class="input" placeholder="确认密码"
      autocomplete="new-password" @keyup.enter="onSubmit" />
    <p v-if="error" class="err">{{ error }}</p>
    <button class="btn" :disabled="loading" @click="onSubmit">
      {{ loading ? '注册中…' : '注 册' }}
    </button>
    <p class="foot">已有账号？
      <router-link to="/login" style="color:var(--accent)">去登录</router-link>
    </p>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { apiLogin, apiRegister } from '@/api'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const username = ref('')
const password = ref('')
const confirm = ref('')
const error = ref('')
const loading = ref(false)

async function onSubmit() {
  error.value = ''
  const u = username.value.trim()
  if (u.length < 3) { error.value = '用户名至少 3 位'; return }
  if (password.value.length < 6) { error.value = '密码至少 6 位'; return }
  if (password.value !== confirm.value) { error.value = '两次密码不一致'; return }
  loading.value = true
  try {
    await apiRegister(u, password.value)
    const data = await apiLogin(u, password.value) // 注册成功自动登录
    userStore.setLogin(data.token, data.user)
    router.replace('/')
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
