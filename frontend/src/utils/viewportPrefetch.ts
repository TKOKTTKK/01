import { prefetchStockDetail } from './detailPrefetch'
import { onIdle, shouldSkipPreload } from './preload'

/**
 * "停下来即取"：持续跟踪哪些列表行当前在视口内，但只在滚动真正停下来
 * （SCROLL_IDLE_MS 内没有新的滚动事件）时，才对那一刻可见的行发起预取。
 *
 * 【为什么不是"一进入视口就预取"】早期版本是行一进视口（threshold 0.6）
 * 就立刻安排一次预取。问题是快速下滑浏览时，中途路过的每一行都会触发
 * 安排，很容易在用户真正停下来、准备点击的那一行到达之前，就把预取
 * 配额和带宽花在了路过的几行上——预取的还是用户"划过去"而不是
 * "正在看"的股票。现在把"记录可见状态"和"发起预取"拆成两步，只有真正
 * 停下来的那一刻，才对当前可见的行发起预取，命中率明显更高。
 *
 * 【v3.3 改并发】原来逐行错峰发起（STAGGER_MS 间隔），是在还没有"真实点击
 * 取消其他预取"这个机制时，用错峰给真实点击预留带宽的权宜之计。现在两个
 * 前提都变了：① 实测确认后端走 HTTP/2（多路复用，不受浏览器 HTTP/1.1
 * 每域名 6 连接上限影响），一屏的请求本来就不需要主动错开来避免排队；
 * ② 真实点击发生时会主动 cancelOtherPrefetches（见 detailPrefetch.ts），
 * 不再需要靠"预取本来就还没发出去"这种被动方式来避让。错峰只剩下拖慢
 * 预取完成时间这一个效果，去掉，改成一屏内一次性并发发出。
 *
 * 【流量控制，现状】
 * 1. 每次"停下来"最多处理 MAX_PER_STOP(20) 行——正常手机一屏 8~12 行，
 *    这个上限只是防止极端情况（超大屏/超小行高）一次性发太多请求，
 *    不是拿来跨屏幕限流的；
 * 2. 每个 code 60 秒内最多尝试一次（ATTEMPT_TTL），这是为了不对同一只
 *    股票在短时间内重复发请求，跟"这一屏能不能被覆盖"是两件事；
 * 3. 命中"停下来"之后仍推迟到浏览器空闲（onIdle）再真正发请求，不与滚动
 *    收尾的渲染/绘制抢主线程；一屏内的请求会在同一个空闲时间片里一起发出；
 * 4. 真实点击发生时，同一屏内其他还在飞行中的预取请求会被取消
 *    （cancelOtherPrefetches），把带宽让给真正要展示的这一个；
 * 5. 省流量模式 / 2G 网络整体跳过（与路由预载同一判定）。
 *
 * 【实现位置说明】统一收敛到 StockRow 组件（每行观察自己 + 模块级共享
 * observer/滚动监听），一份代码即可覆盖 Home/Market/Watchlist/Search
 * 全部列表视图，也天然兼容 VirtualStockList 的虚拟滚动
 * （窗口外的行本来就没挂载，不会被误判为"可见"）。
 */

const ATTEMPT_TTL = 60_000
/** 这一次"停下来"最多处理多少行——防极端情况的软上限，不是跨屏幕的累计配额 */
const MAX_PER_STOP = 20
/** 停止滚动多久后视为"停下来了"——太短会在滚动惯性收尾时误触发，太长则失去"即时"的意义 */
const SCROLL_IDLE_MS = 300

/** code -> 上次尝试时间，用于避免短时间内重复预取同一只股票 */
const attempted = new Map<string, number>()
/** 观察元素 -> 取当前 code 的函数（行组件复用时 props 会变，用 getter 取最新值） */
const codeGetters = new WeakMap<Element, () => string>()
/** 当前处于视口内的元素（持续维护，不代表已发起预取） */
const visible = new Set<Element>()

let observer: IntersectionObserver | null = null
let scrollTimer: number | undefined
let scrollBound = false

function prune(now: number): void {
  for (const [code, t] of attempted) {
    if (now - t > ATTEMPT_TTL) attempted.delete(code)
  }
}

/** 滚动停下来的那一刻触发：对当前可见的行按从上到下的视觉顺序、一次性并发发起预取 */
function prefetchVisibleNow(): void {
  if (shouldSkipPreload()) return
  const now = Date.now()
  prune(now)

  // 按屏幕上的实际位置从上到下排序，而不是按进入视口的时间顺序，
  // 保证同一屏内超过 MAX_PER_STOP 时，优先覆盖的顺序跟视觉顺序一致
  const ordered = [...visible].sort((a, b) => {
    const ta = a.getBoundingClientRect().top
    const tb = b.getBoundingClientRect().top
    return ta - tb
  })

  let scheduled = 0
  for (const el of ordered) {
    if (scheduled >= MAX_PER_STOP) break
    const getCode = codeGetters.get(el)
    if (!getCode) continue
    const code = getCode()
    if (attempted.has(code)) continue
    attempted.set(code, now)
    // 已经等到"停下来"这一刻，onIdle 的超时只是让滚动收尾的渲染先走完；
    // 不再额外错峰，这一屏合格的行会在同一个空闲时间片里一次性并发发出
    onIdle(() => prefetchStockDetail(code), 300)
    scheduled++
  }
}

function scheduleStopCheck(): void {
  window.clearTimeout(scrollTimer)
  scrollTimer = window.setTimeout(prefetchVisibleNow, SCROLL_IDLE_MS)
}

function bindScrollListener(): void {
  if (scrollBound) return
  scrollBound = true
  // .page 没有自己的滚动容器，是整个文档在滚动，监听 window 即可覆盖全部列表页
  window.addEventListener('scroll', scheduleStopCheck, { passive: true })
}

function ensureObserver(): IntersectionObserver | null {
  if (observer) return observer
  if (typeof IntersectionObserver === 'undefined') return null
  observer = new IntersectionObserver((entries) => {
    for (const e of entries) {
      if (e.isIntersecting) visible.add(e.target)
      else visible.delete(e.target)
    }
  }, { threshold: 0.3 })
  return observer
}

/** StockRow 挂载时调用；持续观察（不 unobserve），进出视口只更新可见集合 */
export function observeStockRow(el: Element, getCode: () => string): void {
  const ob = ensureObserver()
  if (!ob) return
  codeGetters.set(el, getCode)
  ob.observe(el)
  bindScrollListener()
  // 页面刚打开、还没发生滚动时，首屏行也要有一次"停下来"判定，
  // 否则用户不滑动就直接点的场景反而享受不到预取
  scheduleStopCheck()
}

/** StockRow 卸载时调用 */
export function unobserveStockRow(el: Element): void {
  observer?.unobserve(el)
  codeGetters.delete(el)
  visible.delete(el)
}
