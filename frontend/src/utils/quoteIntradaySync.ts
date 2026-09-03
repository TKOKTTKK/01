import { getIntraday, getQuote } from '@/api'
import {
  getIntradayCacheAgeMs, getQuoteCacheAgeMs, getSyncMeta, setSyncMeta,
  writeIntradayDiskCache, writeQuoteDiskCache
} from './chartDiskCache'
import { buildPriorityList, canProceed } from './fullSync'
import { onIdle } from './preload'
import { runPool } from './syncPool'
import { isTradingHours } from './tradingHours'

/**
 * 全量后台静默同步：分时图 + 实时行情快照版本，调度骨架跟 fullSync.ts
 * （日K全量同步）完全一样——网络空闲才推进、同一套优先级排序
 * （buildPriorityList，自选股 > 热门榜 > 其余）、分批小并发、断点续传、
 * low-priority 请求——唯一不同的是"要不要重新拉一遍全池"的判断规则，
 * 因为分时图/行情跟日K不是同一种"新鲜度"的东西：
 *
 * 【日K vs 分时图+行情：为什么调度节奏不能共用】
 * 日K是"今天收盘前都不会再变"的静态快照，一天做完一轮全量同步就够，
 * fullSync.ts 靠"今天有没有完整跑完一轮"这一个条件就能表达清楚。
 * 分时图和行情不是这样——交易时段内价格分钟级在跳，同一天里"完整跑完
 * 一轮"不代表"接下来都不用管了"，所以这里的调度规则是：
 * - 交易时段内：每 ROUND_INTERVAL_MS（30 分钟）重新扫一轮全池；
 * - 非交易时段（含开盘前、午间休市、收盘后、周末）：一天只扫一轮——
 *   开盘前/收盘后行情不会变，没必要反复拉；这一轮不特别区分"是不是
 *   刚好在交易时段之外发生"，只看"今天有没有至少完整跑完一轮"，跑过
 *   就不再重复，直到下一个自然日或者进了交易时段触发按 30 分钟重新扫描。
 *
 * 【"增量预取"在这里是什么意思】分时图接口（/api/stocks/{code}/intraday）
 * 和行情接口都不支持像 K 线那样的 since 参数——没有"只要变化部分"的
 * 服务端接口，每次请求拿到的都是当天完整快照，没法在协议层面做增量。
 * 这里说的"增量"落在客户端这一层：全量同步覆盖的是"整个股票池"，但
 * 具体到每一只股票，先查本地磁盘缓存的年龄（getQuoteCacheAgeMs /
 * getIntradayCacheAgeMs，完全不发请求，纯本地判断）——只要本地已经有
 * 当天的数据、且写入时间还在本轮间隔以内，就跳过、不发请求；这份"较新
 * 的本地数据"不一定是全量同步自己上一轮写的，用户翻列表划过视口
 * （viewportPrefetch/detailPrefetch）或者点开详情页顺手写的一样算数——
 * 来源不重要，只要够新。这样"全量"（覆盖全部股票）和"增量"（只对真正
 * 需要更新的那些发请求）就是同一件事的两个层面，不冲突。
 * 唯一需要坦白的局限：由于没有"是否有更新"的轻量探测接口，"数据有没有
 * 变"是靠"距离上次写入是否超过一轮间隔"这个新鲜度窗口推断出来的，不是
 * 真的问一次服务器"变了没有"——极端情况下（比如某只股票超过 30 分钟
 * 都没有一笔成交）会多发一次其实没变化的请求，这是当前后端接口能力下
 * 能做到的最好近似。
 *
 * 【和其它两套机制的边界，避免读者以为三选一】
 * - viewportPrefetch/detailPrefetch：只覆盖用户此刻在看/即将点的那几只，
 *   目标是"这一次交互瞬时"。
 * - visiblePricePolling：交易时段内，只对"这一刻可视区看得见的几只"
 *   每 5 秒轮一次价格，目标是"正在看的股票价格跳得够勤"。
 * - quoteIntradaySync（本文件）：不管用户此刻在看什么，覆盖"整个股票池"，
 *   目标是"划到没预取过的位置 / 点开很久没看的股票，也不是完全没有较
 *   新的分时图和行情"，跟 fullSync.ts 对日K做的事是同一个目标，只是
 *   针对的数据类型和调度节奏不同。
 * 三者互不冲突，写盘目标（chartDiskCache 的 intraday/quote 表）共用，
 * 按 code 主键覆盖，谁写后谁生效。
 */

/** 交易时段内，两轮全量扫描之间的间隔；也直接当作"本地缓存多久内算新鲜、可以跳过"的窗口 */
const ROUND_INTERVAL_MS = 30 * 60 * 1000
/** 分成几批：跟 fullSync.ts 保持一致的折中取值 */
const BATCH_COUNT = 8
/** 每批内部最多同时飞几个"股票"的请求（未命中新鲜度窗口时，每只股票最多 quote+intraday 两个并发请求） */
const POOL_SIZE = 6
/** 批与批之间让出的空闲时间片超时 */
const YIELD_TIMEOUT = 1500
/** 首次调度延迟：比 fullSync.ts 的日K同步稍晚一点启动，错开首屏之后同一时刻两套全量同步抢带宽的高峰 */
const START_DELAY = 12000
/** 前台常驻时，多久主动醒来检查一次"是否到了该开始下一轮全量扫描的时间"——
 *  不能只靠 visibilitychange，用户可能一直停在前台不切出去，页面本身不会
 *  收到任何"过了 30 分钟"的通知，需要自己定时探一下 */
const CHECK_INTERVAL_MS = 5 * 60 * 1000

interface SyncState {
  /** 当天用的完整同步顺序，一天内固定不变，只有跨自然日才重新生成 */
  codes: string[]
  /** 断点续传：这一轮处理到第几个下标（不含），恢复时从这里继续 */
  nextIndex: number
  /** 本地日期字符串，跨自然日要重新生成 codes、重新开始计数 */
  dateStr: string
  /** 上一轮"完整跑完整个股票池"的时间戳；null 表示今天还一轮都没跑完过。
   *  交易时段内用它 + ROUND_INTERVAL_MS 判断该不该开始下一轮；非交易时段
   *  只要不是 null 就说明今天已经跑过（"只预取一次"就是靠这个字段满足的，
   *  不需要额外的标记位）。 */
  lastCompletedAt: number | null
}

function todayStr(): string {
  const d = new Date()
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`
}

function idleYield(timeout = YIELD_TIMEOUT): Promise<void> {
  return new Promise(resolve => onIdle(() => resolve(), timeout))
}

const META_KEY = 'quoteIntradaySyncState'

async function loadOrInitState(): Promise<SyncState> {
  const today = todayStr()
  const existing = await getSyncMeta<SyncState>(META_KEY)
  if (existing && existing.dateStr === today) return existing
  const codes = await buildPriorityList()
  const fresh: SyncState = { codes, nextIndex: 0, dateStr: today, lastCompletedAt: null }
  await setSyncMeta(META_KEY, fresh)
  return fresh
}

/**
 * 判断现在要不要开始/继续跑一轮：上一轮还没跑完就是断点续传，不算"新开一轮"；
 * 上一轮已经跑完的话，交易时段内满一个间隔就再开一轮，非交易时段今天已经
 * 跑过就不再开——这一个函数就是文件头注释里"调度规则"的全部落地。
 */
function shouldRun(state: SyncState): boolean {
  if (state.nextIndex < state.codes.length) return true
  if (state.lastCompletedAt === null) return true
  return isTradingHours() && (Date.now() - state.lastCompletedAt >= ROUND_INTERVAL_MS)
}

/**
 * 单只股票的同步：先查本地缓存年龄（零网络开销），quote 和 intraday 分开
 * 判断——两者过期节奏理论上可能不同步（比如一个刚被详情页刷新过，另一个
 * 没有），谁新鲜就跳过谁，不新鲜的才发请求，尽量把"不必要的请求"降到
 * 最少，而不是只要有一个不新鲜就把两个都重新拉一遍。
 */
async function syncOne(code: string): Promise<void> {
  const [quoteAge, intradayAge] = await Promise.all([
    getQuoteCacheAgeMs(code),
    getIntradayCacheAgeMs(code)
  ])
  const quoteFresh = quoteAge !== null && quoteAge < ROUND_INTERVAL_MS
  const intradayFresh = intradayAge !== null && intradayAge < ROUND_INTERVAL_MS
  if (quoteFresh && intradayFresh) return // 本地都还新鲜，不发请求

  const tasks: Promise<void>[] = []
  if (!quoteFresh) {
    tasks.push(
      getQuote(code, undefined, true).then(q => writeQuoteDiskCache(code, q))
    )
  }
  if (!intradayFresh) {
    tasks.push(
      getIntraday(code, undefined, true).then(v => writeIntradayDiskCache(code, v))
    )
  }
  try {
    await Promise.all(tasks)
  } catch {
    // 单只股票失败（网络错误/超时/该股票临时下架）静默跳过，不影响这一批
    // 其它股票；quote/intraday 哪个失败了，下一轮自然会因为"不新鲜"重试
  }
}

async function processBatch(codes: string[]): Promise<void> {
  await runPool(codes, POOL_SIZE, syncOne)
}

async function runLoop(state: SyncState): Promise<void> {
  const batchSize = Math.max(1, Math.ceil(state.codes.length / BATCH_COUNT))
  while (state.nextIndex < state.codes.length) {
    if (!canProceed()) return // 切后台/弱网：原地停止，靠 kickOff 的触发点重新拾起
    const batch = state.codes.slice(state.nextIndex, state.nextIndex + batchSize)
    await processBatch(batch)
    state.nextIndex += batch.length
    await setSyncMeta(META_KEY, state)
    if (state.nextIndex < state.codes.length) await idleYield()
  }
  state.lastCompletedAt = Date.now()
  await setSyncMeta(META_KEY, state)
}

let syncing = false

async function kickOff(): Promise<void> {
  if (syncing || !canProceed()) return
  syncing = true
  try {
    const state = await loadOrInitState()
    if (shouldRun(state)) {
      if (state.nextIndex >= state.codes.length) state.nextIndex = 0 // 上一轮已跑完，开始新一轮
      await runLoop(state)
    }
  } catch {
    // 静默：全量同步本身失败不应该影响 App 任何其他功能
  } finally {
    syncing = false
  }
}

let started = false

/**
 * 入口：main.ts 挂载完成后调用一次，跟 scheduleFullSync() 平级、各自独立。
 * 内部自己处理"今天要不要跑/跑到哪了/该不该开始下一轮"，外部不需要关心，
 * 也不需要重复调用防抖——多次调用是安全的（syncing 标记防重入）。
 */
export function scheduleQuoteIntradaySync(): void {
  if (started) return
  started = true
  onIdle(() => kickOff(), START_DELAY)
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') kickOff()
  })
  // 交易时段每 30 分钟要开始新一轮，光靠 visibilitychange 等不到（用户可能
  // 全程停留在前台不切走），额外挂一个定时探测；kickOff 本身足够轻量
  // （syncing/canProceed/shouldRun 三层判断，绝大多数调用直接短路返回），
  // 5 分钟探测一次不会有明显开销
  window.setInterval(() => kickOff(), CHECK_INTERVAL_MS)
}
