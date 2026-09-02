import { dropStockPrefetch, onPrefetchCancelled, prefetchStockDetailBatch } from './detailPrefetch'
import { shouldSkipPreload } from './preload'

/**
 * "进入视口即取"：股票行一进入视口（threshold 0.3）就立即把它加入一个
 * 有容量上限的 LRU 追踪队列，同一个短合并窗口内新加入队列的股票会被
 * 打包成一次批量请求发出，不逐只单独发起、也不等滚动停下来。
 *
 * 【v4.0 从"停下来即取"改成"进入即取"】早期版本（见 git 历史）是滚动停止
 * 300ms 后才对那一刻可见的行发起预取，理由是"一进视口就取"在快速划屏时
 * 会把预取配额和带宽花在中途路过的行上。现在改用"LRU 队列 + 容量上限"
 * 来对冲同一个问题：即使划得很快，队列容量只有 MAX_TRACKED(13)，路过的
 * 行会持续把更早进入的行挤出队列——真正"划过去"而不是"正在看"的股票，
 * 大概率还没等到合并窗口触发发请求，就已经被后面进入的行挤出了追踪范围
 * （见 track() 里"入队才会 pending，挤出队列不追加 pending"的逻辑）。
 * 换句话说，节流点从"时间维度（idle 300ms）"换成了"数量维度（队列容量）"，
 * 但都是为了不把预取浪费在用户"划过去"的行上，且响应更即时——用户在
 * 一行上稍作停留（哪怕没到"停止滚动"的程度），这一行就已经在请求路上了。
 *
 * 【队列容量 13 的含义】不是"一屏最多显示多少行"（那个由 visible 集合和
 * 屏幕高度自然决定，没有硬上限），是"当前需要保持新鲜预取数据的股票数"
 * 上限，同时也是单次批量请求打包的 code 数上限（后端 /detail-bootstrap/batch
 * 有同样的校验，见 DetailBootstrapBatchRequest）。正常一屏可见行数
 * （8~12 行）小于 13，淘汰基本只在超大屏/异常场景触发，属于防御性上限。
 *
 * 【v4.1 离开视口即中断】早期版本里"离开视口/被队列淘汰"只摘队列、不撤回
 * 网络请求——理由是好网络下这点并发无所谓，取消反而浪费已经在路上、
 * 马上就能回来的数据。但实测快速划过几百只股票时，这类"注定不会被看到"
 * 的预取会大量堆积，成为真实的带宽和请求数压力，也是"网络波动"的来源
 * 之一。现在改为：一行离开视口（untrack，含被 LRU 挤出、组件卸载）就
 * 调用 detailPrefetch.dropStockPrefetch 撤回它的预取——但只撤回"还没
 * 落地"的部分（数据已经到手的 entry 继续留着，划回来直接复用，不重新
 * 发请求）；同一合并窗口打包的批量请求，只有批次里所有股票都已经划出
 * 视口才会真的 abort 掉那次网络调用，避免连累同批次里仍然可见的其它
 * 股票。真正的"重新进入视口"由 track() 重新判定：撤回时去重标记
 * （attempted）也会被一并清掉（见 dropStockPrefetch → onPrefetchCancelled
 * 回调），所以划回来一定会发起一次全新请求，不受 60 秒去重窗口影响。
 * 弱网下的 cancelOtherPrefetches 仍是另一套独立机制（真实点击时触发），
 * 不和这里的视口离开检测混在一起。
 *
 * 【合并窗口】"进入即取"和"打包成一个请求"本身有一点张力——真的逐个
 * 立即发，就没法打包了。这里用一个很短（MERGE_WINDOW_MS）的合并窗口，
 * 收集窗口内新进入队列、且确实需要发请求的 code，窗口结束时一次性打包
 * 调用批量接口。窗口本身不重新调度（同一批未落地的 code 只会等同一个
 * 定时器），保证一屏内连续进入视口的多行大概率落进同一次批量请求。
 *
 * 【去重】60 秒内尝试过的 code 默认不重复发起（attempted 表），避免同一
 * 只股票被短时间内反复预取；但这个默认值会被 dropStockPrefetch 的结果
 * 覆盖——只要这只股票在离开视口时数据还没落地（entry.done 为 false），
 * 撤回时就会顺带清掉 attempted 里的标记，重新进入视口一定会发起全新请求
 * （不受 60 秒窗口限制）。只有"已经落地"的情况才真正吃到 60 秒去重：
 * 数据已经到手，划回来复用缓存即可，没必要再发一次。
 *
 * 【省流量场景】省流量模式 / 2G 网络整体跳过（与路由预载同一判定）。
 *
 * 【实现位置说明】统一收敛到 StockRow 组件（每行观察自己 + 模块级共享
 * observer），一份代码即可覆盖 Home/Market/Watchlist/Search 全部列表
 * 视图，也天然兼容 VirtualStockList 的虚拟滚动（窗口外的行本来就没
 * 挂载，不会被误判为"可见"）。
 */

const ATTEMPT_TTL = 60_000
/** LRU 追踪队列容量：同时保持新鲜预取数据的股票数上限，也是单次批量请求
 *  打包的 code 数上限，跟后端 DetailBootstrapBatchRequest 的 @Size 对齐 */
const MAX_TRACKED = 13
/** 合并窗口：进入视口后最多等这么久，把窗口内陆续进入的股票收进同一次批量请求 */
const MERGE_WINDOW_MS = 50

/** code -> 上次尝试时间，用于避免短时间内重复预取同一只股票（正常离开视口不清） */
const attempted = new Map<string, number>()
// v3.4 修复：detailPrefetch 弱网下取消某只股票的预取时，这里的去重记录
// 得跟着清掉，否则用户之后划回来看这只股票，会被这张表当成"刚试过"
// 直接跳过，白白晾到 60 秒自然过期才能重新预取
onPrefetchCancelled((code) => attempted.delete(code))
/** 观察元素 -> 取当前 code 的函数（行组件复用时 props 会变，用 getter 取最新值） */
const codeGetters = new WeakMap<Element, () => string>()
/** 当前处于视口内的元素（持续维护，供 getVisibleCodes 查询，无容量上限） */
const visible = new Set<Element>()
/** LRU 追踪队列：code -> 进入队列的时间，Map 保留插入顺序，最早的 key 就是最久没刷新的 */
const tracked = new Map<string, number>()
/** 当前合并窗口内、确认需要发请求的 code 集合 */
let pending = new Set<string>()
let mergeTimer: number | undefined

let observer: IntersectionObserver | null = null

function prune(now: number): void {
  for (const [code, t] of attempted) {
    if (now - t > ATTEMPT_TTL) attempted.delete(code)
  }
}

/** 队列超过容量时，挤掉最早进入的那个：摘出队列、清去重标记，并撤回它
 *  "还没落地"的预取——被挤出队列意味着这只股票大概率早就划出视口了
 *  （容量 13 远小于一屏行数，见文件头注释），跟真正离开视口是同一件事，
 *  处理方式也保持一致（dropStockPrefetch，只撤回未落地的部分） */
function evictIfNeeded(): void {
  while (tracked.size > MAX_TRACKED) {
    const oldest = tracked.keys().next().value
    if (oldest === undefined) break
    tracked.delete(oldest)
    attempted.delete(oldest)
    pending.delete(oldest)
    dropStockPrefetch(oldest)
  }
}

function flushPending(): void {
  mergeTimer = undefined
  if (pending.size === 0) return
  const codes = [...pending]
  pending = new Set()
  if (shouldSkipPreload()) return
  const now = Date.now()
  for (const code of codes) attempted.set(code, now)
  prefetchStockDetailBatch(codes)
}

function scheduleFlush(): void {
  if (mergeTimer !== undefined) return // 已经有一个窗口在等，新加入的 code 会搭上这一班
  mergeTimer = window.setTimeout(flushPending, MERGE_WINDOW_MS)
}

/** 一行进入视口：立即入队，若确实需要发请求（未去重命中）则加进当前合并窗口 */
function track(code: string): void {
  if (shouldSkipPreload()) return
  const now = Date.now()
  prune(now)
  if (tracked.has(code)) return // 已在队列里，不重复入队/不重置顺序，避免抖动来回滑动反复刷新
  tracked.set(code, now)
  evictIfNeeded()
  if (!tracked.has(code)) return // 极端场景：容量为 0 之类，理论不会发生，防御一下
  if (!attempted.has(code)) {
    pending.add(code)
    scheduleFlush()
  }
}

/** 一行离开视口（滚出屏幕，或组件卸载）：摘出队列，并撤回它"还没落地"的
 *  预取（dropStockPrefetch 内部会顺带清掉去重标记，重新进入视口会发起
 *  全新请求，见文件头注释【v4.1 离开视口即中断】） */
function untrack(code: string): void {
  tracked.delete(code)
  pending.delete(code)
  dropStockPrefetch(code)
}

function ensureObserver(): IntersectionObserver | null {
  if (observer) return observer
  if (typeof IntersectionObserver === 'undefined') return null
  observer = new IntersectionObserver((entries) => {
    for (const e of entries) {
      const getCode = codeGetters.get(e.target)
      if (e.isIntersecting) {
        visible.add(e.target)
        if (getCode) track(getCode())
      } else {
        visible.delete(e.target)
        if (getCode) untrack(getCode())
      }
    }
  }, { threshold: 0.3 })
  return observer
}

/**
 * StockRow 挂载时调用；持续观察（不 unobserve），进出视口只更新可见集合。
 * IntersectionObserver 对新 observe() 的元素会立即触发一次初始回调
 * （反映当前是否已经在视口内），所以首屏行（页面刚打开、还没发生滚动）
 * 天然会走到 track()，不需要像旧版那样另外手动触发一次判定。
 */
export function observeStockRow(el: Element, getCode: () => string): void {
  const ob = ensureObserver()
  if (!ob) return
  codeGetters.set(el, getCode)
  ob.observe(el)
}

/** StockRow 卸载时调用：视图切换/虚拟列表回收行时，同样要从队列里摘除，
 *  否则这只股票会一直占着追踪队列的名额，直到被别的行挤出去 */
export function unobserveStockRow(el: Element): void {
  const getCode = codeGetters.get(el)
  observer?.unobserve(el)
  codeGetters.delete(el)
  visible.delete(el)
  if (getCode) untrack(getCode())
}

/**
 * 供 visiblePricePolling.ts 查询"当前屏幕可见的股票代码有哪些"——复用
 * 同一个 IntersectionObserver 的可见集合，不再为轮询单独起一个 observer
 * （同一批 DOM 元素被两个 observer 各自观察一遍是纯浪费）。不保证顺序、
 * 不做去重之外的处理，调用方按需处理。注意这是"当前可见"集合，跟上面的
 * LRU 追踪队列（tracked）是两回事：可见集合没有容量上限，天然受屏幕
 * 高度约束；追踪队列是"预取还要不要为它保留名额"的独立判断。
 */
export function getVisibleCodes(): string[] {
  const codes: string[] = []
  for (const el of visible) {
    const getCode = codeGetters.get(el)
    if (getCode) codes.push(getCode())
  }
  return codes
}
