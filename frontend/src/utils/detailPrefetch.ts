import {
  getDetailBootstrap, getIndicators, getIntraday, getKline, getQuote, getStock
} from '@/api'
import type { Indicators, Intraday, KlineItem, Quote, StockItem } from '@/api/types'

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
 */
const TTL_MS = 8000

export interface Entry {
  time: number
  stock: Promise<StockItem>
  quote: Promise<Quote>
  intraday: Promise<Intraday>
  /** deep 预取才有：日K + 指标（与详情页"日K"Tab 的默认请求参数一致） */
  klineDay?: Promise<KlineItem[]>
  indicatorsDay?: Promise<Indicators>
}

const cache = new Map<string, Entry>()

/** 标记 rejection 已被处理（不冒泡到全局兜底），同时不影响真正的消费方拿到错误 */
function swallow<T>(p: Promise<T>): Promise<T> {
  p.catch(() => { /* 预取失败静默；消费方 await 时仍会收到原始错误 */ })
  return p
}

function makeEntry(code: string): Entry {
  // 聚合接口失败时降级为三次独立请求；memo 化保证三个字段共享同一组降级请求
  let legacy: { stock: Promise<StockItem>; quote: Promise<Quote>; intraday: Promise<Intraday> } | null = null
  const ensureLegacy = () => {
    if (!legacy) {
      legacy = {
        stock: swallow(getStock(code)),
        quote: swallow(getQuote(code)),
        intraday: swallow(getIntraday(code))
      }
    }
    return legacy
  }
  const boot = getDetailBootstrap(code)
  boot.catch(() => { /* 由下方各字段的 catch 分支处理 */ })
  return {
    time: Date.now(),
    stock: swallow(boot.then(b => b.stock).catch(() => ensureLegacy().stock)),
    quote: swallow(boot.then(b => b.quote).catch(() => ensureLegacy().quote)),
    intraday: swallow(boot.then(b => b.intraday).catch(() => ensureLegacy().intraday))
  }
}

function fresh(code: string): Entry | null {
  const hit = cache.get(code)
  return hit && Date.now() - hit.time < TTL_MS ? hit : null
}

function deepen(entry: Entry, code: string): void {
  if (entry.klineDay) return
  entry.klineDay = swallow(getKline(code, 'day'))
  entry.indicatorsDay = swallow(getIndicators(code, 'day'))
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
