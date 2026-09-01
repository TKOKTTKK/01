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
  // 主动取消（比如预取被真实点击顶掉）不是网络故障，不弹提示，
  // 原样透传给调用方，走 detailPrefetch 里已有的 swallow() 静默处理
  if (axios.isCancel(err) || err?.code === 'ERR_CANCELED') {
    return Promise.reject(err)
  }
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

/** 请求并解包 Result；code!=0 抛错，401 类错误跳登录。
 *  signal 可选：传入后，调用方可以用 AbortController 主动取消这次请求
 *  （目前用在预取场景——真实点击发生时，取消其他还在飞行中的预取请求，
 *  把带宽让给真正要展示的这一个）。 */
export async function request<T>(config: {
  url: string
  method?: 'get' | 'post' | 'delete'
  params?: Record<string, unknown>
  data?: unknown
  signal?: AbortSignal
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

/**
 * 面向"预取"这类低优先级请求的轻量封装，绕开 axios 直接走原生 fetch——
 * 目的是用上浏览器原生的 Fetch Priority（fetch(url, { priority: 'low' })）。
 * axios 默认走 XHR 适配器，XHR 完全不支持这个参数；即便切到 axios 的
 * fetch 适配器，官方也没有文档化、跨版本稳定的方式把 priority 这种额外
 * 字段透传进去，直接用原生 fetch 更可靠、行为可预期，代价是这里要自己
 * 重复一遍 baseURL / 鉴权头 / ApiResult 解包 / 401 跳登录的逻辑
 * （跟上面 http 实例、request() 保持同样的语义，只是换了个传输方式）。
 *
 * 不支持 Priority Hints 的浏览器（目前的 Safari）会静默忽略 priority 这个字段，
 * 不认识的属性不会导致报错，等同于没设置，行为上退化为普通优先级请求。
 *
 * 只应该用在真正"投机性、可能白发"的请求上（详情页数据预取）；正常的
 * 页面数据加载、真实点击后的请求，继续走上面的 request()，不要用这个——
 * 优先级这件事本身就是相对的，如果所有请求都标 low，等于都没标。
 */
export async function requestLowPriority<T>(config: {
  url: string
  params?: Record<string, unknown>
  signal?: AbortSignal
}): Promise<T> {
  const store = useUserStore()
  const base = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
  const entries = Object.entries(config.params ?? {}).filter(([, v]) => v !== undefined && v !== null)
  const qs = entries.length
    ? `?${new URLSearchParams(entries.map(([k, v]) => [k, String(v)])).toString()}`
    : ''

  let resp: Response
  try {
    resp = await fetch(`${base}${config.url}${qs}`, {
      method: 'GET',
      headers: store.token ? { Authorization: `Bearer ${store.token}` } : undefined,
      signal: config.signal,
      // priority 是 Fetch Priority API 的字段，标准 lib.dom.d.ts 版本不一定收录，
      // 用类型断言而不是依赖 TS 版本，避免因为编译环境差异导致类型报错
      ...({ priority: 'low' } as Record<string, unknown>)
    })
  } catch (err) {
    if ((err as { name?: string })?.name === 'AbortError') throw err // 主动取消，交给调用方的 swallow() 静默处理
    notifyNetworkError('网络异常，请检查网络连接')
    throw err
  }
  if (!resp.ok) {
    if (resp.status >= 500) notifyNetworkError('服务暂时不可用，请稍后重试')
    throw new Error(`HTTP ${resp.status}`)
  }
  const body = await resp.json() as ApiResult<T>
  if (body.code === 0) return body.data
  if (body.code === 40100 || body.code === 40101) {
    store.logout()
    router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
  }
  throw new Error(body.message || '请求失败')
}

export default http
