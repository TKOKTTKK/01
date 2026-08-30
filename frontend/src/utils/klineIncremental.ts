import { getIndicators, getKline } from '@/api'
import type { Indicators, KlineItem, Period } from '@/api/types'
import { readKlineDiskCacheRaw, writeKlineDiskCache } from './chartDiskCache'

/**
 * K线增量拉取：本地磁盘有历史基础（不看展示新鲜度，见 readKlineDiskCacheRaw）
 * 就只问后端要「比本地最新一天更新」的部分，合并到本地历史后面；本地完全
 * 没有缓存（第一次看这只股票/这个周期）就照常全量请求。
 *
 * 请求成功后把合并后的完整序列写回磁盘缓存（覆盖旧的、带上新的 ts），
 * 下次增量拉取会以这次合并后的结果为基础，越用越省流量。
 *
 * 【为什么合并逻辑放在这一层，不是 StockDetailView/detailPrefetch 各写一份】
 * 两处（详情页 loadKline、touchstart 深度预取 deepen）都需要"要 K线就顺带
 * 增量+合并+写盘"这一整套逻辑，写两份容易慢慢跑偏（比如一个改了合并规则
 * 另一个忘了改），收敛成一个函数，两处都只管调用。
 */
export async function fetchKlineIncremental(
  code: string, period: Period, signal?: AbortSignal, lowPriority?: boolean
): Promise<{ k: KlineItem[]; i: Indicators }> {
  const local = await readKlineDiskCacheRaw(code, period)

  if (!local || local.kline.length === 0) {
    const [k, i] = await Promise.all([
      getKline(code, period, undefined, undefined, signal, lowPriority),
      getIndicators(code, period, undefined, undefined, signal, lowPriority)
    ])
    writeKlineDiskCache(code, period, k, i).catch(() => { /* 静默 */ })
    return { k, i }
  }

  const sinceDate = local.kline[local.kline.length - 1].date
  const [newKline, newIndicators] = await Promise.all([
    getKline(code, period, undefined, sinceDate, signal, lowPriority),
    getIndicators(code, period, undefined, sinceDate, signal, lowPriority)
  ])

  const k = mergeKline(local.kline, newKline)
  const i = mergeIndicators(local.indicators, newIndicators)

  writeKlineDiskCache(code, period, k, i).catch(() => { /* 静默 */ })
  return { k, i }
}

/** 本地缓存太久没打开时，增量合并出来的序列理论上可以无限增长，跟后端 MAX_LIMIT 对齐做个上限 */
const MAX_LOCAL_CANDLES = 500

function trimHead<T>(arr: T[]): T[] {
  return arr.length > MAX_LOCAL_CANDLES ? arr.slice(arr.length - MAX_LOCAL_CANDLES) : arr
}

function mergeKline(local: KlineItem[], incoming: KlineItem[]): KlineItem[] {
  if (incoming.length === 0) return local
  const lastLocalDate = local[local.length - 1].date
  // 后端已经按 date > sinceDate 过滤，这里按日期再筛一遍是双保险，防止任何边界重复
  const appended = incoming.filter(item => item.date > lastLocalDate)
  return trimHead(local.concat(appended))
}

function mergeIndicators(local: Indicators, incoming: Indicators): Indicators {
  if (incoming.dates.length === 0) return local
  const lastLocalDate = local.dates[local.dates.length - 1]
  const from = incoming.dates.findIndex(d => d > lastLocalDate)
  if (from === -1) return local // 增量部分跟本地已有的完全重叠，没有可合并的新内容

  return {
    dates: trimHead(local.dates.concat(incoming.dates.slice(from))),
    ma: {
      ma5: trimHead(local.ma.ma5.concat(incoming.ma.ma5.slice(from))),
      ma10: trimHead(local.ma.ma10.concat(incoming.ma.ma10.slice(from))),
      ma20: trimHead(local.ma.ma20.concat(incoming.ma.ma20.slice(from))),
      ma60: trimHead(local.ma.ma60.concat(incoming.ma.ma60.slice(from)))
    },
    macd: {
      dif: trimHead(local.macd.dif.concat(incoming.macd.dif.slice(from))),
      dea: trimHead(local.macd.dea.concat(incoming.macd.dea.slice(from))),
      macd: trimHead(local.macd.macd.concat(incoming.macd.macd.slice(from)))
    },
    kdj: {
      k: trimHead(local.kdj.k.concat(incoming.kdj.k.slice(from))),
      d: trimHead(local.kdj.d.concat(incoming.kdj.d.slice(from))),
      j: trimHead(local.kdj.j.concat(incoming.kdj.j.slice(from)))
    },
    rsi: {
      rsi6: trimHead(local.rsi.rsi6.concat(incoming.rsi.rsi6.slice(from))),
      rsi12: trimHead(local.rsi.rsi12.concat(incoming.rsi.rsi12.slice(from))),
      rsi24: trimHead(local.rsi.rsi24.concat(incoming.rsi.rsi24.slice(from)))
    }
  }
}
