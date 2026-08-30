import { getQuotesBatch } from '@/api'
import type { StockItem } from '@/api/types'
import { getVisibleCodes } from './viewportPrefetch'
import { shouldSkipPreload } from './preload'
import { useMarketStore } from '@/stores/market'
import { useWatchlistStore } from '@/stores/watchlist'

/**
 * 可视区高频价格轮询：全量后台同步（fullSync.ts）解决的是"日K这类静态
 * 快照要不要重新拉"，交易时段内价格是随时变的，快照数据不该也不能替代
 * 实时价格。这里只做一件很小的事——每隔几秒，把"当前屏幕上看得见的
 * 那几只股票"的最新价格重新取一遍，patch 到已有的行情数据里，不重新
 * 拉取整页/整个股票池。
 *
 * 【为什么不是复用 stores/market.ts 里已有的 10 秒轮询】那一套刷新的是
 * "已经加载出来的所有页"（用户滚动了多少页，就刷新多少页），量级跟着
 * 用户滚动深度线性增长，且没有交易时间判断，非交易时段一样在跑。这里是
 * 加一层单独的、只覆盖"这一刻真正看得见的几只"、只在交易时段跑的轮询——
 * 两者不冲突：10 秒那一档继续负责"整体列表结构"（新股、排序、总数这些
 * 不那么高频变化的东西），这里只负责"看得见的几只股票，价格要跳得更勤"。
 *
 * 【覆盖范围】目前 patch 到 market.stocks / market.hot / watchlist.list
 * 这三处——是用户在首页/行情页/自选页浏览时最常见的来源。搜索结果页
 * 的实时跳价暂不在范围内：搜索场景通常是"搜到就点"，停留时间短，
 * 收益远低于前三个列表页，为了这个场景单独接一路патch不值得，
 * 后续如果发现搜索页也有长时间停留浏览的使用模式，再考虑加。
 *
 * 【调度】跟 fullSync 共用同一套"低打扰"原则，但请求本身走正常优先级
 * （不是 low）——这批股票是用户此刻真的在看的，不是投机性的。
 */

const POLL_MS = 5000
/** 安全上限：正常一屏可见行数远低于这个数字，纯粹是防极端情况（超大屏/虚拟列表异常） */
const MAX_CODES = 40

function inTradingHours(): boolean {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Shanghai',
    hour12: false,
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit'
  }).formatToParts(new Date())
  const get = (type: string) => parts.find(p => p.type === type)?.value ?? ''
  const weekday = get('weekday')
  if (weekday === 'Sat' || weekday === 'Sun') return false
  // 不含法定节假日判断——客户端没有交易日历数据源，节假日当天会误判成
  // "在交易时间"，届时价格本来就不变，多轮询几次不会展示错误内容，
  // 顶多是白白发了几个不产生变化的请求，不是正确性问题，先不做这层
  const hour = Number(get('hour'))
  const minute = Number(get('minute'))
  const mins = hour * 60 + minute
  const morning = mins >= 9 * 60 + 30 && mins <= 11 * 60 + 30
  const afternoon = mins >= 13 * 60 && mins <= 15 * 60
  return morning || afternoon
}

/** 按 code 把新价格 patch 进已有数组（原地修改，不替换数组本身，触发的是逐行而非整页重渲染） */
function patchInto(list: StockItem[], byCode: Map<string, { price: number; changeAmount: number; changePercent: number }>): void {
  for (const item of list) {
    const q = byCode.get(item.code)
    if (!q) continue
    item.price = q.price
    item.changeAmount = q.changeAmount
    item.changePercent = q.changePercent
  }
}

let polling = false

async function tick(): Promise<void> {
  if (polling) return // 上一轮还没回来就跳过这一轮，不重叠发请求
  if (document.visibilityState !== 'visible' || shouldSkipPreload() || !inTradingHours()) return
  const codes = getVisibleCodes().slice(0, MAX_CODES)
  if (codes.length === 0) return

  polling = true
  try {
    const quotes = await getQuotesBatch(codes)
    const byCode = new Map(quotes.map(q => [q.code, q]))
    const market = useMarketStore()
    patchInto(market.stocks, byCode)
    patchInto(market.hot, byCode)
    try {
      patchInto(useWatchlistStore().list, byCode)
    } catch { /* 未登录/store 不可用，忽略 */ }
  } catch {
    // 静默：这一轮没更新到，5 秒后下一轮自然重试，不弹全局错误提示
    // （高频轮询偶尔失败是正常噪声，不该打断用户）
  } finally {
    polling = false
  }
}

let started = false

/** main.ts 挂载完成后调用一次；内部自己按 POLL_MS 定时检查，不需要外部管理生命周期 */
export function startVisiblePricePolling(): void {
  if (started) return
  started = true
  window.setInterval(tick, POLL_MS)
}
