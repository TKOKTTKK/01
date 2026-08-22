import axios from 'axios'
import { useUserStore } from '@/stores/user'
import router from '@/router'

/** 统一 API 返回结构 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

const http = axios.create({ baseURL: import.meta.env.VITE_API_BASE_URL || '/', timeout: 10000 })

http.interceptors.request.use((config) => {
  const store = useUserStore()
  if (store.token) {
    config.headers.Authorization = `Bearer ${store.token}`
  }
  return config
})

/**
 * 网络层错误（超时 / 断网 / 5xx）给一次全局提示。
 * 做节流是因为页面里同时有多个轮询，断网时会瞬间抛出一堆同样的错误，
 * 不限流会刷屏。业务错误（code != 0）仍由各页面自行处理。
 */
let lastNetToastAt = 0
function notifyNetworkError(message: string) {
  const now = Date.now()
  if (now - lastNetToastAt < 5000) return
  lastNetToastAt = now
  import('@/stores/ui')
    .then(({ useUiStore }) => useUiStore().toast(message, 'error'))
    .catch(() => { /* store 不可用时忽略 */ })
}

http.interceptors.response.use((resp) => resp, (err) => {
  const isTimeout = err?.code === 'ECONNABORTED'
  const status = err?.response?.status
  let message = '网络异常，请检查网络连接'
  if (isTimeout) {
    message = '请求超时，请重试'
  } else if (status && status >= 500) {
    message = '服务暂时不可用，请稍后重试'
  }
  notifyNetworkError(message)
  return Promise.reject(new Error(message))
})

/** 请求并解包 Result；code!=0 抛错，401 类错误跳登录 */
export async function request<T>(config: {
  url: string
  method?: 'get' | 'post' | 'delete'
  params?: Record<string, unknown>
  data?: unknown
}): Promise<T> {
  const resp = await http.request<ApiResult<T>>({ method: 'get', ...config })
  const body = resp.data
  if (body.code === 0) return body.data
  if (body.code === 40100 || body.code === 40101) {
    useUserStore().logout()
    router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
  }
  throw new Error(body.message || '请求失败')
}

export default http
