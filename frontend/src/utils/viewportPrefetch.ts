import { prefetchStockDetail } from './detailPrefetch'
import { onIdle, shouldSkipPreload } from './preload'

/**
 * "可见即取"：列表行进入视口就预取该股票的详情页首屏数据（detail-bootstrap）。
 *
 * 【与 touchstart 预取的关系】互补，不是替代：
 * - touchstart/mouseenter 命中"即将点击"这个更精确的时机（并追加日K deep 预取）；
 * - 可见即取覆盖"用户可能会点但没碰过屏幕就直接点"的场景
 *   （尤其鼠标/触控板直接点击时没有 touchstart，mouseenter 到 click 也只差几十毫秒）。
 * 两者写入 detailPrefetch 的同一个 cache，沿用同一套 TTL 判重逻辑，不会重复请求。
 *
 * 【流量控制】
 * 1. 预算制：60 秒滑动窗口内最多发起 BUDGET(8) 次可见预取
 *    ——即"首屏可见的前 6-8 只"，滚动出来的更多行不会全量预取；
 * 2. 每个 code 60 秒内最多尝试一次（ATTEMPT_TTL），行反复进出视口不重复发；
 * 3. 一律推迟到浏览器空闲（onIdle）再发，不与首屏关键请求抢带宽；
 * 4. 省流量模式 / 2G 网络整体跳过（与路由预载同一判定）。
 *
 * 【实现位置说明】需求描述的是"在含列表的视图里挂 IntersectionObserver"；
 * 这里统一收敛到 StockRow 组件（每行观察自己 + 模块级共享 observer），
 * 一份代码即可覆盖 Home/Market/Watchlist/Search 全部列表视图，
 * 避免在 3-4 个视图里重复维护 DOM ref 收集与 observer 生命周期。
 */

const ATTEMPT_TTL = 60_000
const BUDGET = 8

/** code -> 上次尝试时间 */
const attempted = new Map<string, number>()
/** 观察元素 -> 取当前 code 的函数（行组件复用时 props 会变，用 getter 取最新值） */
const codeGetters = new WeakMap<Element, () => string>()

let observer: IntersectionObserver | null = null

function prune(now: number): void {
  for (const [code, t] of attempted) {
    if (now - t > ATTEMPT_TTL) attempted.delete(code)
  }
}

function tryPrefetch(code: string): void {
  const now = Date.now()
  prune(now)
  if (attempted.has(code)) return          // 60 秒内已尝试过
  if (attempted.size >= BUDGET) return     // 预算用完（窗口滑动后自动恢复）
  attempted.set(code, now)
  onIdle(() => prefetchStockDetail(code), 1500)
}

function ensureObserver(): IntersectionObserver | null {
  if (observer) return observer
  if (typeof IntersectionObserver === 'undefined') return null
  observer = new IntersectionObserver((entries) => {
    if (shouldSkipPreload()) return
    for (const e of entries) {
      if (!e.isIntersecting) continue
      const getCode = codeGetters.get(e.target)
      if (getCode) tryPrefetch(getCode())
    }
  }, { threshold: 0.6 })
  return observer
}

/** StockRow 挂载时调用；持续观察（不 unobserve），进出视口由 ATTEMPT_TTL 节流 */
export function observeStockRow(el: Element, getCode: () => string): void {
  const ob = ensureObserver()
  if (!ob) return
  codeGetters.set(el, getCode)
  ob.observe(el)
}

/** StockRow 卸载时调用 */
export function unobserveStockRow(el: Element): void {
  observer?.unobserve(el)
  codeGetters.delete(el)
}
