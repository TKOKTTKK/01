import { abortPrefetchIfPending, onPrefetchCancelled, prefetchStockDetail } from './detailPrefetch'
import { onIdle, shouldSkipPreload } from './preload'

/**
 * "慢下来即取，划出即停"：持续跟踪哪些列表行当前在视口内；滚动速度慢
 * 下来（不需要等到完全静止）就对那一刻可见的行发起预取；一旦某一行
 * 滑出视口，如果它的预取请求还没回来，立刻取消——带宽永远只留给
 * 当前可视范围内的股票。
 *
 * 【v3.7：从"停下来"改成"慢下来"】早期版本要等 SCROLL_IDLE_MS(300ms)内
 * 完全没有新的滚动事件才触发，本质是"等滚动彻底静止"。这次改成持续
 * 跟踪滚动速度：一旦速度降到 SLOW_VELOCITY 阈值以下（用户正在减速，
 * 即将停下但还没完全停），用更短的 FAST_CHECK_MS 去触发预取，不用再
 * 傻等到完全静止那一刻——手指离屏后的惯性滚动（momentum scrolling）
 * 天然会经历"从快到慢"这个过程，新逻辑能在这个过程后段就提前反应，
 * 比等它完全停下来更快命中。如果滚动一直很快（用户还在使劲划），
 * 依然退化成 SCROLL_IDLE_MS 的"没有新事件就当作停了"这条安全网，
 * 不会因为一直没有出现过"慢速采样点"而永远等不到触发。
 *
 * 【v3.7：新增"划出即停"】原来一行的预取请求一旦发出就不会因为它划出
 * 视口而取消（除非真实点击触发的弱网清场）。现在只要 IntersectionObserver
 * 报告某一行不再可见，无条件、立刻取消它的预取（如果还有的话）——不像
 * 真实点击那条路径只在弱网时才取消，这里没有"好网络就放过"这一说：
 * 已经看不见的股票不管网络好坏都不再有"即将被点开"的价值，继续花
 * 带宽在它身上纯粹是浪费，永远优先满足当前可视范围内的股票。
 *
 * 【为什么不是"一进入视口就预取"】早期版本是行一进视口就立刻安排一次
 * 预取。问题是快速下滑浏览时，中途路过的每一行都会触发安排，很容易
 * 在用户真正停下来、准备点击的那一行到达之前，就把预取配额和带宽花
 * 在了路过的几行上——预取的还是用户"划过去"而不是"正在看"的股票。
 * 现在把"记录可见状态"和"发起预取"拆成两步，只有滚动慢下来那一刻，
 * 才对当前可见的行发起预取，命中率明显更高，也是"划出即停"能生效的前提
 * ——如果一进视口就发，那一刻还谈不上"是不是即将划出"，无从谈起取消。
 *
 * 【流量控制，现状】
 * 1. 每次触发最多处理 MAX_PER_STOP(20) 行——正常手机一屏 8~12 行，
 *    这个上限只是防止极端情况（超大屏/超小行高）一次性发太多请求，
 *    不是拿来跨屏幕限流的；
 * 2. 每个 code 60 秒内最多尝试一次（ATTEMPT_TTL），这是为了不对同一只
 *    股票在短时间内重复发请求，跟"这一屏能不能被覆盖"是两件事；
 * 3. 触发后仍推迟到浏览器空闲（onIdle）再真正发请求，不与滚动收尾的
 *    渲染/绘制抢主线程；一屏内的请求会在同一个空闲时间片里一起发出；
 * 4. 真实点击发生时，仅在弱网/省流量场景下才取消同一屏内其他还在飞行中的
 *    预取请求（见 detailPrefetch.ts 的 cancelOtherPrefetches）；行滑出
 *    视口则无条件取消（见上面的 abortPrefetchIfPending），两者是不同的
 *    取舍——前者两只股票都还在屏幕上，后者已经确定看不见了；
 * 5. 省流量模式 / 2G 网络整体跳过（与路由预载同一判定）。
 *
 * 【实现位置说明】统一收敛到 StockRow 组件（每行观察自己 + 模块级共享
 * observer/滚动监听），一份代码即可覆盖 Home/Market/Watchlist/Search
 * 全部列表视图，也天然兼容 VirtualStockList 的虚拟滚动
 * （窗口外的行本来就没挂载，不会被误判为"可见"）。
 */

const ATTEMPT_TTL = 60_000
/** 这一次触发最多处理多少行——防极端情况的软上限，不是跨屏幕的累计配额 */
const MAX_PER_STOP = 20
/** 滚动速度低于这个值（像素/毫秒）视为"慢下来了"，可以提前触发 */
const SLOW_VELOCITY = 0.3
/** 检测到"慢下来"之后的短防抖——不用等完全静止，但也不是慢一点就立刻发 */
const FAST_CHECK_MS = 100
/** 还在快速滚动时的兜底防抖：这段时间内没有新的滚动事件，也当作"停了" */
const SCROLL_IDLE_MS = 300

/** code -> 上次尝试时间，用于避免短时间内重复预取同一只股票 */
const attempted = new Map<string, number>()
// detailPrefetch 取消某只股票的预取时（弱网清场 / 划出视口），这里的
// 去重记录得跟着清掉，否则用户之后划回来看这只股票，会被这张表当成
// "刚试过"直接跳过，白白晾到 60 秒自然过期才能重新预取
onPrefetchCancelled((code) => attempted.delete(code))
/** 观察元素 -> 取当前 code 的函数（行组件复用时 props 会变，用 getter 取最新值） */
const codeGetters = new WeakMap<Element, () => string>()
/** 当前处于视口内的元素（持续维护，不代表已发起预取） */
const visible = new Set<Element>()

let observer: IntersectionObserver | null = null
let scrollTimer: number | undefined
let scrollBound = false
let lastScrollY = 0
let lastScrollT = 0

function prune(now: number): void {
  for (const [code, t] of attempted) {
    if (now - t > ATTEMPT_TTL) attempted.delete(code)
  }
}

/** 触发时刻：对当前可见的行按从上到下的视觉顺序、一次性并发发起预取 */
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
    // 已经判定"慢下来/停了"，onIdle 的超时只是让滚动收尾的渲染先走完；
    // 不再额外错峰，这一屏合格的行会在同一个空闲时间片里一次性并发发出
    onIdle(() => prefetchStockDetail(code), 300)
    scheduled++
  }
}

/**
 * 每次滚动事件都会算一次瞬时速度，据此决定这次要等多久再触发：
 * 已经慢下来 -> 短防抖，尽快反应；还很快 -> 长防抖，当作"没停"，
 * 等真的没有新事件进来一段时间才当作停了（兜底，避免一直判断"很快"
 * 导致永远不触发）。
 */
function onScroll(): void {
  const now = performance.now()
  const y = window.scrollY
  const dt = now - lastScrollT
  const dy = Math.abs(y - lastScrollY)
  lastScrollY = y
  lastScrollT = now

  const velocity = dt > 0 ? dy / dt : 0
  window.clearTimeout(scrollTimer)
  const delay = velocity < SLOW_VELOCITY ? FAST_CHECK_MS : SCROLL_IDLE_MS
  scrollTimer = window.setTimeout(prefetchVisibleNow, delay)
}

function bindScrollListener(): void {
  if (scrollBound) return
  scrollBound = true
  lastScrollY = window.scrollY
  lastScrollT = performance.now()
  // .page 没有自己的滚动容器，是整个文档在滚动，监听 window 即可覆盖全部列表页
  window.addEventListener('scroll', onScroll, { passive: true })
}

function ensureObserver(): IntersectionObserver | null {
  if (observer) return observer
  if (typeof IntersectionObserver === 'undefined') return null
  observer = new IntersectionObserver((entries) => {
    for (const e of entries) {
      if (e.isIntersecting) {
        visible.add(e.target)
      } else {
        visible.delete(e.target)
        // 划出即停：不管这一刻是不是弱网，只要还有预取请求在飞就立刻取消
        const getCode = codeGetters.get(e.target)
        if (getCode) abortPrefetchIfPending(getCode())
      }
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
  // 页面刚打开、还没发生滚动时，首屏行也要有一次触发判定，
  // 否则用户不滑动就直接点的场景反而享受不到预取
  window.clearTimeout(scrollTimer)
  scrollTimer = window.setTimeout(prefetchVisibleNow, SCROLL_IDLE_MS)
}

/** StockRow 卸载时调用 */
export function unobserveStockRow(el: Element): void {
  observer?.unobserve(el)
  codeGetters.delete(el)
  visible.delete(el)
}

/**
 * 供 visiblePricePolling.ts 查询"当前屏幕可见的股票代码有哪些"——复用
 * 同一个 IntersectionObserver 的可见集合，不再为轮询单独起一个 observer
 * （同一批 DOM 元素被两个 observer 各自观察一遍是纯浪费）。不保证顺序、
 * 不做去重之外的处理，调用方按需处理。
 */
export function getVisibleCodes(): string[] {
  const codes: string[] = []
  for (const el of visible) {
    const getCode = codeGetters.get(el)
    if (getCode) codes.push(getCode())
  }
  return codes
}
