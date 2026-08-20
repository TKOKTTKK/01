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

http.interceptors.response.use((resp) => resp, (err) => {
  return Promise.reject(new Error(err?.message || '网络错误'))
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
