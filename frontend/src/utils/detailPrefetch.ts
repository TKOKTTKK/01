import {
  getDetailBootstrap, getDetailBootstrapBatch, getIntraday, getQuote, getStock
} from '@/api'
import type { DetailBootstrap, Indicators, Intraday, KlineItem, Quote, StockItem } from '@/api/types'
import { writeIntradayDiskCache, writeQuoteDiskCache } from './chartDiskCache'
import { fetchKlineIncremental } from './klineIncremental'
import { shouldSkipPreload } from './preload'

/**
 * 股票详情页数据预取缓存。
 *
 * 【为什么需要】路由级预加载（preload.ts）只提前下载了详情页的 JS 代码，
 * 但页面里要展示的"这只股票的报价/基础信息/分时"是每次点击才第一次请求的，
 * 这才是点开详情页还要等 1-2 秒的真正原因——不是浏览器不支持，是数据还没到。
 *
 * 【做法】在预取时机到来（列表行进入视口 / 手指碰到行）那一刻，就提前把
 * 这只股票的关键数据请求发出去，缓存 Promise 本身（而不是等结果），详情页
 * onMounted 时直接复用这个 Promise：如果预取已经回来了就是零等待，
 * 没回来也是同一个请求在排队，不会重复发起。
 *
 * 【v3.1 变化】
 * 1. 底层从「getStock + getQuote + getIntraday 三次请求」换成一次
 *    detail-bootstrap 聚合请求；对外仍暴露 stock/quote/intraday 三个
 *    Promise 字段，消费方（StockDetailView）按字段各自渲染。
 *    聚合接口失败时（如后端还是旧版本的部署间隙）自动降级为三次独立请求。
 * 2. 新增 deep 预取（K线 + 指标，day 周期）：只在 touchstart/mouseenter
 *    这种"即将点击"的高置信时机触发，可见即取（viewportPrefetch）不带，
 *    避免为首屏 6-8 行股票预取大体积的 K 线数据。
 * 3. 预取 Promise 统一 swallow：预取本质是投机请求，用户可能根本不进
 *    详情页，失败不应触发 main.ts 的全局 unhandledrejection 提示。
 *
 * 【为什么要设 TTL】预取只应该服务"预取 -> 马上点进去"这一小段窗口，
 * 如果用户划走了、几十秒后又点别的股票，不应该用很久以前预取的过期数据。
 *
 * 【v3.2 真实点击优先，v3.4 收窄为仅弱网触发】"停下来即取"改成一屏内并发
 * 发起后（见 viewportPrefetch.ts），一次停留可能同时有好几个预取请求在
 * 飞行。最初的想法是"用户一点击，其他都让路"，实测发现这用力过猛：
 * 好网络下（已确认后端 HTTP/2 多路复用）几个小 JSON 请求跟真实点击的请求
 * 同时飞着完全不会互相拖慢，取消反而是浪费已经发出去、马上就能回来的数据——
 * 表现为"划回去看别的股票，明明刚预取过却没有缓存"。
 * 现在改成：只有 shouldSkipPreload() 判定为弱网/省流量时，才真的取消其他
 * 预取来保护这一次点击；好网络下点击不取消任何东西，让它们自然完成。
 * 做法：每个 Entry 持有自己的 AbortController，贯穿它发出的全部请求
 * （bootstrap / 降级三连 / deep 预取的 K线+指标）；真实导航发生时调用
 * cancelOtherPrefetches(exceptCode)，弱网下把缓存里除了目标股票之外、
 * 还在飞行中的请求取消掉，并通知 viewportPrefetch 把对应股票从"60秒去重表"
 * 里也清掉（见 onPrefetchCancelled）——否则被取消的股票要白白晾 60 秒，
 * 用户划回去也不会重新触发预取。取消不是错误，http.ts 的响应拦截器已经
 * 识别 ERR_CANCELED 并跳过全局报错提示；这里的 swallow() 也会接住，
 * 不会冒泡成未处理异常。
 *
 * 【v4.1 离开视口即撤回】上面的"好网络不取消"说的是真实点击发生时对其它
 * 预取的态度，针对的是"用户已经点开了一只股票，其它预取还要不要抢带宽"；
 * 这里是另一件事——视口预取（见 viewportPrefetch.ts）触发的请求，如果对应
 * 的行本身已经划出视口（用户根本没打算看它了），不管好网络弱网络都应该
 * 撤回，因为这份数据大概率永远不会被消费，纯粹是浪费带宽和请求数，快速
 * 划过几百只股票时这类浪费会累积成明显的网络压力。为此新增
 * dropStockPrefetch：只撤回"还没落地"的预取（entry.done 为 false），
 * 已经到手的数据继续留着复用；批量请求只有整批的股票都划出视口了才真的
 * abort 网络调用，避免连累同批次里仍然可见的其它股票。
 *
 * 【v3.5 Fetch Priority】好网络下不取消，那"预取请求"和"真实点击的请求"
 * 就是完全平等的并发请求，谁也不比谁优先——这不是我们想要的：预取终归是
 * 投机性的，真实点击才是用户此刻真正在等的那一个。本文件发出的每个请求
 * 都统一标了 lowPriority（见各处 getXxx(..., true) 调用），底层走原生
 * fetch + Fetch Priority('low')（见 http.ts 的 requestLowPriority）。
 * 不取消、也不用等真实点击发生才临时降级，从发出的那一刻起就是"低优先级"，
 * 由浏览器网络栈在调度并发请求时自己向真实点击（默认/高优先级）倾斜，
 * 比"要么完全平等、要么一刀切取消"更精细，且不受网络好坏影响都生效。
 */
const TTL_MS = 8000

export interface Entry {
  time: number
  controller: AbortController
  /** stock/quote/intraday 三个字段是否已经全部落地（成功或失败都算）——
   *  离开视口需要决定"能不能安全撤回这只股票的预取"时，只有还没落地
   *  （数据还在路上）的 entry 才值得撤回；已经到手的数据没有理由因为
   *  用户划走了就扔掉，见 dropStockPrefetch。 */
  done: boolean
  stock: Promise<StockItem>
  quote: Promise<Quote>
  intraday: Promise<Intraday>
  /** deep 预取才有：日K + 指标（与详情页"日K"Tab 的默认请求参数一致） */
  klineDay?: Promise<KlineItem[]>
  indicatorsDay?: Promise<Indicators>
}

const cache = new Map<string, Entry>()

/** 批次内部登记表：code -> 该 code 所属批次共享的 controller，以及
 *  "批次里还有哪些 code 仍然需要这次结果"的集合（同一个 Set 对象被批次内
 *  所有 code 共享引用）。只在批次尚未落地期间存在，落地后统一清理，
 *  仅供 dropStockPrefetch 判断"是否整个批次都已经没人要了"使用。 */
const batchMembership = new Map<string, { controller: AbortController; siblings: Set<string> }>()

/**
 * 取消发生时的旁路通知——viewportPrefetch.ts 的"60秒去重表"跟这里是两个
 * 独立模块的状态，取消一个 code 的预取时，需要顺带告诉它"这个 code 可以
 * 重新尝试了"，否则用户划回去也要白等到 60 秒自然过期。用注册回调而不是
 * 让本文件反过来 import viewportPrefetch，避免两个文件互相 import 造成
 * 循环依赖（viewportPrefetch 本来就要 import 这个文件的 prefetchStockDetail）。
 */
const cancelListeners: Array<(code: string) => void> = []
export function onPrefetchCancelled(fn: (code: string) => void): void {
  cancelListeners.push(fn)
}

/** 标记 rejection 已被处理（不冒泡到全局兜底），同时不影响真正的消费方拿到错误 */
function swallow<T>(p: Promise<T>): Promise<T> {
  p.catch(() => { /* 预取失败/被取消静默；消费方 await 时仍会收到原始错误 */ })
  return p
}

/**
 * lowPriority 是显式传入而不是本函数自己决定的——buildEntry 的调用方
 * 优先级含义完全相反：prefetchStockDetail/prefetchStockDetailBatch（投机性
 * 预取）该标 low；getPrefetchedOrFetch 命中缓存落空、现场发起的那次
 * （用户此刻正在等的真实数据）绝不能标 low，否则"没预取过就点开"反而比
 * "预取过再点开"慢。
 *
 * boot 由调用方传入而不是这里现发请求——单只预取（makeEntry）和批量预取
 * （makeBatchEntry）唯一的区别就是这个 Promise 的来源（单只 bootstrap 接口
 * v.s. 从一次批量响应里取自己那一份），拆分/降级/落盘这些后续逻辑完全共用。
 */
function buildEntry(code: string, boot: Promise<DetailBootstrap>, controller: AbortController, lowPriority: boolean): Entry {
  const { signal } = controller
  // 聚合接口失败时降级为三次独立请求；memo 化保证三个字段共享同一组降级请求
  let legacy: { stock: Promise<StockItem>; quote: Promise<Quote>; intraday: Promise<Intraday> } | null = null
  const ensureLegacy = () => {
    if (!legacy) {
      legacy = {
        stock: swallow(getStock(code, signal, lowPriority)),
        quote: swallow(getQuote(code, signal, lowPriority)),
        intraday: swallow(getIntraday(code, signal, lowPriority))
      }
    }
    return legacy
  }
  boot.catch(() => { /* 由下方各字段的 catch 分支处理 */ })

  const intraday = boot.then(b => b.intraday).catch(() => ensureLegacy().intraday)
  // 落一份到磁盘缓存（见 chartDiskCache.ts），供下次打开详情页瞬时展示；
  // 失败（配额满/隐私模式/被取消）静默，不影响本次正常渲染
  intraday.then(v => writeIntradayDiskCache(code, v)).catch(() => { /* 静默 */ })

  const quote = boot.then(b => b.quote).catch(() => ensureLegacy().quote)
  quote.then(v => writeQuoteDiskCache(code, v)).catch(() => { /* 静默 */ })

  const entry: Entry = {
    time: Date.now(),
    controller,
    done: false,
    stock: swallow(boot.then(b => b.stock).catch(() => ensureLegacy().stock)),
    quote: swallow(quote),
    intraday: swallow(intraday)
  }
  // 三个字段全部落地（不管成功失败）才算这只股票"有了结果"——用
  // allSettled 而不是盯 boot 本身，是因为 boot 失败后还会走 ensureLegacy
  // 降级三连，那三个请求落地才是真正的终点，见 dropStockPrefetch 的用法
  Promise.allSettled([entry.stock, entry.quote, entry.intraday]).then(() => { entry.done = true })
  return entry
}

function makeEntry(code: string, lowPriority: boolean): Entry {
  const controller = new AbortController()
  const boot = getDetailBootstrap(code, controller.signal, lowPriority)
  return buildEntry(code, boot, controller, lowPriority)
}

/**
 * batchPromise 是"一次批量请求"的共享 Promise，多个 code 会 .then() 同一个
 * 对象——每只股票各自拆出属于自己的那一份，取不到（后端跳过了这个 code）
 * 时正常走 ensureLegacy 单独降级重试，不影响批次里其它股票。
 *
 * 注意 controller 是每只股票各自独立的一个，不是批量请求本身用的那个——
 * 批量请求的网络调用只在 prefetchStockDetailBatch 里发生一次，不挂在任何
 * 单只股票的 controller 上；这里的 controller 只用来控制这只股票自己的
 * 降级请求 / deep 预取（K线），保证"淘汰/取消某一只股票的预取"不会连带
 * 影响批次里其它股票仍在等待的同一个批量响应。
 */
function makeBatchEntry(code: string, batchPromise: Promise<Record<string, DetailBootstrap>>, controller: AbortController): Entry {
  const boot = batchPromise.then(m => {
    const b = m[code]
    if (!b) throw new Error(`批量结果缺少 ${code}`)
    return b
  })
  return buildEntry(code, boot, controller, true) // 批量预取统一低优先级
}

function fresh(code: string): Entry | null {
  const hit = cache.get(code)
  return hit && Date.now() - hit.time < TTL_MS ? hit : null
}

function deepen(entry: Entry, code: string): void {
  if (entry.klineDay) return
  // 增量拉取：本地磁盘有历史缓存就只问后端要新增部分，见 klineIncremental.ts
  const combined = fetchKlineIncremental(code, 'day', entry.controller.signal, true)
  // 注意：分别对派生出的两个 .then() 单独 swallow，而不是只 swallow combined 本身——
  // combined 本身有没有 catch 不影响它派生出的子 Promise 是否会成为"未处理的 rejection"，
  // 两个子 Promise 各自需要有人接住，用户全程没进详情页（entry 从未被消费）时同样如此。
  entry.klineDay = swallow(combined.then(v => v.k))
  entry.indicatorsDay = swallow(combined.then(v => v.i))
}

/**
 * 单只预取入口。
 * - 即将点击（touchstart/mouseenter）：prefetchStockDetail(code, { deep: true })
 *   —— 追加日K + 指标，覆盖"用户是冲着看K线来的"场景
 * 进入视口这一档改走下面的 prefetchStockDetailBatch（见 viewportPrefetch.ts），
 * 不再逐只调用这个函数；两者写的是同一个 cache，TTL 判重，不会重复发起。
 */
export function prefetchStockDetail(code: string, opts?: { deep?: boolean }): void {
  let entry = fresh(code)
  if (!entry) {
    entry = makeEntry(code, true) // 投机性预取，标低优先级
    cache.set(code, entry)
  }
  if (opts?.deep) deepen(entry, code)
}

/**
 * 批量预取入口：viewportPrefetch.ts 把同一个合并窗口内新进入视口的多只
 * 股票（最多 13 个）一次性传进来，这里只发一次网络请求，回来后按 code
 * 拆分成各自独立的 Entry 写入同一份 cache，详情页读取路径
 * （getPrefetchedOrFetch）无感知，跟单只预取写进去的 Entry 没有区别。
 *
 * 只按 fresh(code) 过滤——语义跟 prefetchStockDetail 一致，已经有新鲜
 * （TTL 内）数据的股票不重复占用这次批量请求的名额。是否需要发起这次调用
 * 的去重（60 秒内是否已经尝试过）由调用方（viewportPrefetch 的 attempted
 * 表）负责，这里不重复判断。
 */
export function prefetchStockDetailBatch(codes: string[]): void {
  const need = codes.filter(code => !fresh(code))
  if (need.length === 0) return
  // 批量请求本身的网络调用不挂在任何单只股票的 controller 上（见
  // makeBatchEntry 的注释），而是登记进 batchMembership：批次里的股票
  // 逐个离开视口时，dropStockPrefetch 会从 siblings 里摘掉对应 code；
  // 只有这个批次里的股票全部划出视口（siblings 空了），才会真正 abort
  // 掉这一次批量网络请求——只要还有一个 code 仍在视口内，这次请求就该
  // 继续跑完，不能因为别的行划走了就连累它拿不到数据。
  const batchController = new AbortController()
  const batchPromise = swallow(getDetailBootstrapBatch(need, batchController.signal))
  const siblings = new Set(need)
  const cleanup = () => { for (const code of need) batchMembership.delete(code) }
  batchPromise.then(cleanup, cleanup) // 正常落地/失败都要清理登记表，避免内存泄漏
  for (const code of need) {
    batchMembership.set(code, { controller: batchController, siblings })
    const entryController = new AbortController()
    cache.set(code, makeBatchEntry(code, batchPromise, entryController))
  }
}

/**
 * 视口预取专用：一行离开视口（滚出屏幕 / 虚拟列表回收）时调用，撤回这只
 * 股票"还没到手"的预取。
 *
 * - 若数据已经落地（entry.done）：什么都不做，留着它——已经到手的数据
 *   没理由因为划走了就扔掉，划回来正好是零等待，这也是预取本身的意义。
 * - 若还在同一个批次里、批次尚未落地：把这只股票从批次的 siblings 集合里
 *   摘掉；只有摘完这个批次一个仍需要的 code 都不剩，才真正 abort 掉那次
 *   批量网络请求——保证不会为了一只股票离开就连累同批次里仍然可见的
 *   其它股票。
 * - 同时 abort 这只股票自己的 entryController，覆盖它独有的降级请求
 *   （聚合接口失败后的三连）和 deep 预取（K线+指标），这部分不会影响
 *   批次里的兄弟 code。
 * - 无论是否真的 abort 了网络请求，都要把 cache 里这只股票的 entry 摘掉，
 *   并通知 60 秒去重表（attempted）一起清掉——保证重新进入视口时会发起
 *   一次全新的请求，而不是命中一个已经作废的 entry，或者卡在去重表里
 *   白等 60 秒才能重新预取。
 */
export function dropStockPrefetch(code: string): void {
  const entry = cache.get(code)
  if (!entry || entry.done) return
  const member = batchMembership.get(code)
  if (member) {
    batchMembership.delete(code)
    member.siblings.delete(code)
    if (member.siblings.size === 0) member.controller.abort()
  }
  entry.controller.abort()
  cache.delete(code)
  for (const fn of cancelListeners) fn(code)
}

/** 详情页读取：命中新鲜预取就直接复用，否则现场发起请求（真实需要，不投机，走正常优先级） */
export function getPrefetchedOrFetch(code: string): Entry {
  const hit = fresh(code)
  if (hit) return hit
  const entry = makeEntry(code, false)
  cache.set(code, entry)
  return entry
}

/** 只探测、不发请求：详情页用它判断 deep 预取的日K是否可直接复用 */
export function getFreshEntry(code: string): Entry | null {
  return fresh(code)
}

/**
 * 真实导航发生时调用：只有在弱网/省流量场景下，才取消缓存里除了目标股票
 * 之外、仍在飞行中的预取请求，把带宽让给真正要展示的这一个——好网络下
 * HTTP/2 多路复用扛得住这点并发，取消反而是纯浪费，直接跳过。
 *
 * 【为什么直接删掉 entry，而不是留着等它"取消后"的状态】被取消的请求
 * 不会再有正常结果，留在 cache 里没有意义。同时通知
 * viewportPrefetch 的去重表也一并清掉，用户之后划回来看到这只股票，
 * 会正常触发一次全新的预取，不会因为① 命中一个"已作废"的 entry 拿到
 * abort 错误，或者 ② 卡在去重表里白等 60 秒才能重新预取。
 */
export function cancelOtherPrefetches(exceptCode: string): void {
  if (!shouldSkipPreload()) return // 好网络：不取消，让其他预取自然完成
  for (const [code, entry] of cache) {
    if (code === exceptCode) continue
    entry.controller.abort()
    cache.delete(code)
    for (const fn of cancelListeners) fn(code)
  }
}
