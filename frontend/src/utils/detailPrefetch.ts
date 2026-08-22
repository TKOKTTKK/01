import { getIntraday, getQuote, getStock } from '@/api'
import type { Intraday, Quote, StockItem } from '@/api/types'

/**
 * 股票详情页数据预取缓存。
 *
 * 【为什么需要】路由级预加载（preload.ts）只提前下载了详情页的 JS 代码，
 * 但页面里要展示的"这只股票的报价/基础信息/分时"是每次点击才第一次请求的，
 * 这才是点开详情页还要等 1-2 秒的真正原因——不是浏览器不支持，是数据还没到。
 *
 * 【做法】在用户手指刚碰到股票行（touchstart）那一刻，就提前把这只股票的
 * 关键数据请求发出去，缓存 Promise 本身（而不是等结果），详情页 onMounted
 * 时直接复用这个 Promise：如果预取已经回来了就是零等待，没回来也是同一个
 * 请求在排队，不会重复发起。
 *
 * 【为什么要设 TTL】预取只应该服务"手指碰到 -> 马上点进去"这一瞬间的场景，
 * 如果用户划走了、几十秒后又点别的股票，不应该用很久以前预取的过期数据。
 */
const TTL_MS = 8000

interface Entry {
  time: number
  stock: Promise<StockItem>
  quote: Promise<Quote>
  intraday: Promise<Intraday>
}

const cache = new Map<string, Entry>()

export function prefetchStockDetail(code: string): void {
  const hit = cache.get(code)
  if (hit && Date.now() - hit.time < TTL_MS) return // 已有新鲜的预取在途/已完成，不重复发起
  cache.set(code, {
    time: Date.now(),
    stock: getStock(code),
    quote: getQuote(code),
    intraday: getIntraday(code)
  })
}

/** 详情页读取：命中新鲜预取就直接复用，否则现场发起请求 */
export function getPrefetchedOrFetch(code: string): Entry {
  const hit = cache.get(code)
  if (hit && Date.now() - hit.time < TTL_MS) return hit
  const fresh: Entry = { time: Date.now(), stock: getStock(code), quote: getQuote(code), intraday: getIntraday(code) }
  cache.set(code, fresh)
  return fresh
}
