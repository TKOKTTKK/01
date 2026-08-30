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
 * 【v3 修正：一屏内命中不全、位置随机】排查真机反馈"有时候上半屏命中、
 * 有时候下半屏命中、有时候中间命中"发现是两处设计问题，不是随机现象：
 * 1. 原来的 BUDGET 是"过去 60 秒全局最多预取 8 个"，不是"这一屏最多 8 个"——
 *    划过几屏之后配额很快被路过的行占满，后面新出现的整屏都拿不到名额，
 *    直到最早的记录 60 秒后过期才腾出来。现在改成 MAX_PER_STOP：只限制
 *    "这一次停下来"处理多少行，不再跨屏幕、跨时间累计计数。
 * 2. 原来遍历的是 `visible` 这个 Set，遍历顺序 = 元素进入视口的时间顺序，
 *    跟它在屏幕上是靠上还是靠下无关——同一屏内谁先被观察到（取决于滚动
 *    方向），谁就排在前面。现在改成按 getBoundingClientRect().top 从上到下
 *    排序后再处理，保证优先覆盖的顺序跟视觉顺序一致。
 * 3. threshold 从 0.6 降到 0.3：原来要求一行超过 60% 高度进入视口才算
 *    "可见"，屏幕边缘那些用户主观感觉"看得到"但只露出四五成的行，会被
 *    直接排除在预取候选之外。
 *
 * 【与 touchstart 预取的关系】互补，不是替代：
 * - touchstart/mouseenter 命中"即将点击"这个更精确的时机（并追加日K deep 预取）；
 * - 停下来即取覆盖"用户盯着看了一会但还没碰过屏幕"的场景，
 *   给这次真正点击预留更长的提前量。
 * 两者写入 detailPrefetch 的同一个 cache，沿用同一套 TTL 判重逻辑，不会重复请求。
 *
 * 【流量控制，新版】
 * 1. 每次"停下来"最多处理 MAX_PER_STOP(20) 行——正常手机一屏 8~12 行，
 *    这个上限只是防止极端情况（超大屏/超小行高）一次性发太多请求，
 *    不是拿来跨屏幕限流的；
 * 2. 每个 code 60 秒内最多尝试一次（ATTEMPT_TTL），这是为了不对同一只
 *    股票在短时间内重复发请求，跟"这一屏能不能被覆盖"是两件事；
 * 3. 逐行用小间隔错峰发起（STAGGER_MS），避免一次性并发发出十几个请求
 *    挤占行情 API 和主线程；
 * 4. 命中"停下来"之后仍推迟到浏览器空闲（onIdle）再真正发请求，不与滚动
 *    收尾的渲染/绘制抢主线程；
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
/** 逐行错峰发起的间隔，避免同一时刻并发发出一大串请求 */
const STAGGER_MS = 60
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

/** 滚动停下来的那一刻触发：对当前可见的行按从上到下的视觉顺序发起预取 */
function prefetchVisibleNow(): void {
  if (shouldSkipPreload()) return
  const now = Date.now()
  prune(now)

  // 按屏幕上的实际位置从上到下排序，而不是按进入视口的时间顺序，
  // 保证同一屏内谁在上面谁先被处理，跟滚动方向无关
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
    const delay = scheduled * STAGGER_MS
    // 已经等到"停下来"这一刻，onIdle 的超时只是让滚动收尾的渲染先走完，
    // 外面再叠加一层错峰延迟，避免这一屏的行全部挤在同一时刻发请求
    window.setTimeout(() => onIdle(() => prefetchStockDetail(code), 300), delay)
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
