import {
  getDetailBootstrap, getIntraday, getQuote, getStock
} from '@/api'
import type { Indicators, Intraday, KlineItem, Quote, StockItem } from '@/api/types'
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
 */
const TTL_MS = 8000

export interface Entry {
  time: number
  controller: AbortController
  stock: Promise<StockItem>
  quote: Promise<Quote>
  intraday: Promise<Intraday>
  /** deep 预取才有：日K + 指标（与详情页"日K"Tab 的默认请求参数一致） */
  klineDay?: Promise<KlineItem[]>
  indicatorsDay?: Promise<Indicators>
}

const cache = new Map<string, Entry>()

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

function makeEntry(code: string): Entry {
  const controller = new AbortController()
  const { signal } = controller
  // 聚合接口失败时降级为三次独立请求；memo 化保证三个字段共享同一组降级请求
  let legacy: { stock: Promise<StockItem>; quote: Promise<Quote>; intraday: Promise<Intraday> } | null = null
  const ensureLegacy = () => {
    if (!legacy) {
      legacy = {
        stock: swallow(getStock(code, signal)),
        quote: swallow(getQuote(code, signal)),
        intraday: swallow(getIntraday(code, signal))
      }
    }
    return legacy
  }
  const boot = getDetailBootstrap(code, signal)
  boot.catch(() => { /* 由下方各字段的 catch 分支处理 */ })

  const intraday = boot.then(b => b.intraday).catch(() => ensureLegacy().intraday)
  // 落一份到磁盘缓存（见 chartDiskCache.ts），供下次打开详情页瞬时展示；
  // 失败（配额满/隐私模式/被取消）静默，不影响本次正常渲染
  intraday.then(v => writeIntradayDiskCache(code, v)).catch(() => { /* 静默 */ })

  const quote = boot.then(b => b.quote).catch(() => ensureLegacy().quote)
  quote.then(v => writeQuoteDiskCache(code, v)).catch(() => { /* 静默 */ })

  return {
    time: Date.now(),
    controller,
    stock: swallow(boot.then(b => b.stock).catch(() => ensureLegacy().stock)),
    quote: swallow(quote),
    intraday: swallow(intraday)
  }
}

function fresh(code: string): Entry | null {
  const hit = cache.get(code)
  return hit && Date.now() - hit.time < TTL_MS ? hit : null
}

function deepen(entry: Entry, code: string): void {
  if (entry.klineDay) return
  // 增量拉取：本地磁盘有历史缓存就只问后端要新增部分，见 klineIncremental.ts
  const combined = fetchKlineIncremental(code, 'day', entry.controller.signal)
  // 注意：分别对派生出的两个 .then() 单独 swallow，而不是只 swallow combined 本身——
  // combined 本身有没有 catch 不影响它派生出的子 Promise 是否会成为"未处理的 rejection"，
  // 两个子 Promise 各自需要有人接住，用户全程没进详情页（entry 从未被消费）时同样如此。
  entry.klineDay = swallow(combined.then(v => v.k))
  entry.indicatorsDay = swallow(combined.then(v => v.i))
}

/**
 * 预取入口。
 * - 可见即取（IntersectionObserver）：prefetchStockDetail(code) —— 只取 bootstrap
 * - 即将点击（touchstart/mouseenter）：prefetchStockDetail(code, { deep: true })
 *   —— 追加日K + 指标，覆盖"用户是冲着看K线来的"场景
 * 两种时机写同一个 cache，TTL 判重，不会重复发起。
 */
export function prefetchStockDetail(code: string, opts?: { deep?: boolean }): void {
  let entry = fresh(code)
  if (!entry) {
    entry = makeEntry(code)
    cache.set(code, entry)
  }
  if (opts?.deep) deepen(entry, code)
}

/** 详情页读取：命中新鲜预取就直接复用，否则现场发起请求 */
export function getPrefetchedOrFetch(code: string): Entry {
  const hit = fresh(code)
  if (hit) return hit
  const entry = makeEntry(code)
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
