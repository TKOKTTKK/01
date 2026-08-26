import { preloadView, type ViewName } from '@/router'

/**
 * 空闲时预加载路由 chunk。
 *
 * 【为什么要做】所有页面都是懒加载，首次进入任一 Tab 都要等一次网络往返，
 * 在移动网络下有明显的白屏/卡顿感。在浏览器空闲时提前把 chunk 拉下来，
 * 用户真正点击时组件已在内存里，切换是瞬时的。
 *
 * 【对首屏的影响】几乎为零：预加载跑在 requestIdleCallback 里，
 * 只在主线程空闲、首屏关键资源加载完之后才执行，不与首屏抢带宽。
 *
 * 【对流量的影响】会变多：原本"没点过的页面永不下载"，现在每个用户都会
 * 下载全部 Tab 页 + 详情页 + 图表库。对本 App（用户基本会逛遍 5 个 Tab）
 * 这笔交换是划算的；对开启了"省流量模式"的用户则完全跳过。
 */

/** 加载优先级：Tab 页优先，其次是最常从列表点进去的详情页 */
const PRELOAD_ORDER: ViewName[] = [
  'market', 'watchlist', 'trade', 'mine', // 首页通常就是入口，无需预载
  'stock',                                 // 详情页（会连带拉起 echarts chunk）
  'search'
]

type IdleWindow = Window & {
  requestIdleCallback?: (cb: () => void, opts?: { timeout: number }) => number
}

/**
 * requestIdleCallback 的降级封装（Safari 直到 16.4 才支持）。
 * 供路由预载 / 数据预取（viewportPrefetch、详情页日K预取）共用。
 */
export function onIdle(cb: () => void, timeout = 2000): void {
  const w = window as IdleWindow
  if (typeof w.requestIdleCallback === 'function') {
    w.requestIdleCallback(cb, { timeout })
  } else {
    window.setTimeout(cb, timeout)
  }
}

/**
 * 用户开启了省流量模式 / 处于 2G 慢网时，跳过一切「预」动作
 * （路由 chunk 预载、可见即取、日K后台预取共用同一判定）。
 */
export function shouldSkipPreload(): boolean {
  const conn = (navigator as Navigator & {
    connection?: { saveData?: boolean; effectiveType?: string }
  }).connection
  if (!conn) return false
  if (conn.saveData) return true
  return conn.effectiveType === 'slow-2g' || conn.effectiveType === '2g'
}

/**
 * 启动预加载：串行逐个加载，每个之间再让出一次空闲时间片，
 * 避免一次性并发十几个请求挤占行情 API 的带宽。
 */
export function startIdlePreload(): void {
  if (shouldSkipPreload()) return
  let i = 0
  const next = () => {
    if (i >= PRELOAD_ORDER.length) return
    preloadView(PRELOAD_ORDER[i++])
    onIdle(next, 800)
  }
  onIdle(next)
}
