import { getHotStocks, listStocks } from '@/api'
import { fetchKlineIncremental } from './klineIncremental'
import { getSyncMeta, pruneStaleGlobally, setSyncMeta } from './chartDiskCache'
import { onIdle, shouldSkipPreload } from './preload'
import { useWatchlistStore } from '@/stores/watchlist'

/**
 * 全量后台静默同步：把整个股票池（目前 500 只）的日K + 指标提前拉到本地
 * IndexedDB，让"划到任意位置、点开任意股票"都能命中本地缓存瞬时展示，
 * 不再局限于"预取过/点开过才有缓存"。
 *
 * 【和已有预取机制的关系，说清楚不重叠】
 * - viewportPrefetch / detailPrefetch：命中"用户即将看到/点击"的高置信
 *   时机，覆盖的是 quote + intraday（会变的实时数据）+ 可选的日K深度预取，
 *   目标是"这一次交互能不能瞬时"，范围是"当前屏幕附近"。
 * - fullSync（本文件）：不看用户此刻在干什么，只看"整个股票池里，本地
 *   还缺哪些/哪些可能过期了"，覆盖范围是全部股票，但只同步日K+指标——
 *   全量预取的数据定位是"静态快照"，quote/intraday 这类高频变化的数据
 *   不在全量同步范围内，交易时段内可视区股票的实时价格由另一套轻量
 *   轮询负责（后续步骤），不靠全量重新拉一遍来更新价格。
 * 两者用同一个 IndexedDB kline 表，谁先写不冲突（都是按 code+period 覆盖）。
 *
 * 【调度策略】
 * 1. 只在页面处于前台（visibilitychange）且非弱网（shouldSkipPreload）
 *    时推进；切后台/弱网就地停止，不空转、不重试轮询——网络变好/回到
 *    前台会自然触发 visibilitychange 或下次开 App 时的 scheduleFullSync()
 *    重新拾起进度。
 * 2. 按优先级排好整个股票池的顺序（自选股 > 热门榜 > 其余），分 BATCH_COUNT
 *    批，每批内部用小并发池（POOL_SIZE）处理，批与批之间用 onIdle 让出
 *    主线程和网络，不会因为一次性发出几百个请求造成网络抖动。
 * 3. 断点续传：进度（当天用的完整 codes 顺序 + 处理到第几个）存在
 *    IndexedDB 的 meta 表，跨次开 App 记得住；同一天内已经完整跑完一轮的
 *    不会重复跑。
 * 4. 每个请求都走 low-priority（fetchKlineIncremental 的 lowPriority 参数，
 *    跟 requestLowPriority 一样标了 Fetch Priority('low')），保证真的有
 *    用户交互触发的请求（点击、可视区预取）发生时，浏览器网络栈天然向
 *    那些倾斜，不需要额外写"检测用户是否正在高频操作"的逻辑——这是复用
 *    第 3 轮改造已经建好的机制，不是重新发明。
 *
 * 【v3.6：判断"要不要重新拉"的方式换了】最初这里是自己接的一套 HTTP
 * ETag/If-None-Match 条件请求（后端 EtagConfig.java 那套），真机验证时
 * 发现 ETag 没生效——後端配置本身检查下来没发现明显问题，但没有出网权限
 * 没法实测确认卡在哪一环（Railway 边缘代理剥掉了头？还是别的原因），
 * 与其死磕一套自己独立维护、目前又验证不了的机制，不如直接复用项目里
 * 本来就有、详情页点开股票一直在用、经过验证的 fetchKlineIncremental
 * （klineIncremental.ts）——它本身就是"本地有数据就只问增量，本地没数据
 * 就整包拉"，跟这里想要的行为完全一致，不需要另起一套判断逻辑。
 * 代价：换成这条路径后，每只股票的写入变成 fetchKlineIncremental 内部
 * 各自独立的 db.kline.put()，不再是一批股票攒起来一次 bulkPut——写事务
 * 数量从"8 批"变回"500 次"，但这些写入本身分散在整个同步过程（网络
 * 请求耗时远大于单次 IndexedDB 写入），不会集中在某一帧造成卡顿，
 * 换来的是不用再维护一套独立的、目前验证不了是否可靠的条件请求机制，
 * 这个取舍是划算的。后端 EtagConfig.java 保留不动——ETag 本身对浏览器
 * 自己的原生 HTTP 缓存依然有用，只是不再由这里的自定义 JS 逻辑消费。
 */

/** 分成几批：5~10 之间，具体数字对结果影响不大，8 是折中 */
const BATCH_COUNT = 8
/** 每批内部最多同时飞几个"股票"的请求（每只股票是 kline+indicators 两个并发请求，
 *  所以网络上实际同时在飞的请求数约是这个数字的两倍） */
const POOL_SIZE = 6
/** 批与批之间让出的空闲时间片超时 */
const YIELD_TIMEOUT = 1500
/** 首次调度延迟：等应用自己的首屏渲染、路由预载先跑一段，不跟启动关键路径抢 */
const START_DELAY = 5000

interface SyncState {
  /** 今天用的完整同步顺序，跨批次/跨次打开 App 保持稳定，断点续传靠它定位 */
  codes: string[]
  /** 已经处理到第几个下标（不含），恢复时从这里继续 */
  nextIndex: number
  /** 本地日期字符串，跨自然日要重新生成 codes（重新排一次优先级、重新走一轮） */
  dateStr: string
}

function todayStr(): string {
  const d = new Date()
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`
}

function idleYield(timeout = YIELD_TIMEOUT): Promise<void> {
  return new Promise(resolve => onIdle(() => resolve(), timeout))
}

/**
 * 生成本次全量同步的股票顺序：自选股 > 热门榜 > 其余全部（按后端分页顺序）。
 * 任何一档失败都静默跳过、不影响其他档——哪怕只有"其余全部"这一档成功，
 * 同步范围退化成全量但顺序没有优先级，也好过完全不跑。
 */
async function buildPriorityList(): Promise<string[]> {
  const seen = new Set<string>()
  const ordered: string[] = []
  const push = (codes: string[]) => {
    for (const c of codes) {
      if (!seen.has(c)) {
        seen.add(c)
        ordered.push(c)
      }
    }
  }

  try {
    push(useWatchlistStore().list.map(s => s.code))
  } catch { /* 静默 */ }

  try {
    const hot = await getHotStocks(30)
    push(hot.map(s => s.code))
  } catch { /* 静默 */ }

  try {
    let page = 1
    const size = 100 // 后端 MAX_PAGE_SIZE 上限，见 StockService
    // eslint-disable-next-line no-constant-condition
    while (true) {
      const res = await listStocks(page, size)
      push(res.list.map(s => s.code))
      if (res.list.length === 0 || page * size >= res.total) break
      page++
    }
  } catch { /* 静默：至少前面自选+热门已经拿到了 */ }

  return ordered
}

/**
 * 小并发池：从队列里最多同时取 POOL_SIZE 个在跑，跑完一个再取下一个，
 * 不是"批内还要再分批"。同步单只股票直接复用 fetchKlineIncremental——
 * 本地有数据只问增量、没数据整包拉、写盘（含合并）全部由它内部处理，
 * 这里不需要再重复一遍这些逻辑。
 */
async function runPool(codes: string[], worker: (code: string) => Promise<void>): Promise<void> {
  let idx = 0
  async function lane(): Promise<void> {
    while (idx < codes.length) {
      const code = codes[idx++]
      await worker(code)
    }
  }
  await Promise.all(Array.from({ length: Math.min(POOL_SIZE, codes.length) }, lane))
}

async function processBatch(codes: string[]): Promise<void> {
  await runPool(codes, async (code) => {
    try {
      await fetchKlineIncremental(code, 'day', undefined, true)
    } catch {
      // 单只股票失败（网络错误/超时/该股票临时下架）静默跳过，
      // 不影响这一批其他股票，下次同步会自然重试
    }
  })
}

const META_KEY = 'fullSyncState'

async function loadOrInitState(): Promise<SyncState> {
  const today = todayStr()
  const existing = await getSyncMeta<SyncState>(META_KEY)
  if (existing && existing.dateStr === today) return existing
  const codes = await buildPriorityList()
  const fresh: SyncState = { codes, nextIndex: 0, dateStr: today }
  await setSyncMeta(META_KEY, fresh)
  return fresh
}

function canProceed(): boolean {
  return document.visibilityState === 'visible' && !shouldSkipPreload()
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
}

let syncing = false

async function kickOff(): Promise<void> {
  if (syncing || !canProceed()) return
  syncing = true
  try {
    const state = await loadOrInitState()
    if (state.nextIndex < state.codes.length) {
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
 * 入口：main.ts 挂载完成后调用一次。内部自己处理"今天有没有做完/
 * 断点续传到哪了"，外部不需要关心，也不需要重复调用防抖——
 * 多次调用是安全的（syncing 标记防重入）。
 */
export function scheduleFullSync(): void {
  if (started) return
  started = true
  onIdle(() => {
    pruneStaleGlobally()
    kickOff()
  }, START_DELAY)
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') kickOff()
  })
}
