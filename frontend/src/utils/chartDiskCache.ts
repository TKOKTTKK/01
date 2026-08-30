import Dexie, { type Table } from 'dexie'
import type { Indicators, Intraday, KlineItem, Period, Quote } from '@/api/types'

/**
 * 图表磁盘缓存（分时图 + K线），基于 Dexie / IndexedDB。
 *
 * 【定位】只做"瞬时展示层的兜底"，不参与任何"数据是否最新"的判断——
 * 磁盘里的数据可能是几小时甚至（K线）接近 24 小时之前的，它唯一的作用
 * 是"在真实网络请求回来之前，先把上次的样子画出来，别让用户等空白"。
 * 真实请求永远照常发生，回来后静默覆盖磁盘数据展示的内容，用户无感。
 * 这个"先垫后盖"的模式跟本项目里 seed 价格接力、intraday 预取是同一套思路。
 *
 * 【为什么分时图和K线的过期规则不一样】
 * - 分时图是"今天"的数据，形状和价格区间只对当天有意义：跨了自然日再
 *   拿出来展示，看起来会是完全错误的一天（比如开盘价、最高最低价全不对），
 *   比空白更容易让人误解"现在的价格"，所以除了 24 小时 TTL，还要求
 *   必须是同一个自然日，两个条件任一不满足就判失效。
 * - K线（日/周/月）不是这样：哪怕缓存缺了最新一根蜡烛，展示出来的历史
 *   走势依然是"对的"，只是少了最新一天/一周/一月而已，不会造成误导，
 *   所以只按 24 小时 TTL，不强制同一自然日。
 * - 实时行情快照（quote：价格/今开/最高/最低/成交量/成交额）跟分时图
 *   同一个道理——"今开"、"最高"、"最低"都是"今天"的统计口径，跨自然日
 *   拿出来展示会是错的一天，所以规则跟分时图一致：24 小时 TTL + 必须
 *   同一自然日。
 *
 * 【为什么用 Dexie/IndexedDB 而不是 localStorage】
 * K线数据（历史蜡烛 + 指标，三个周期）比分时图大得多，铺开到几百上千只
 * 股票很容易顶到 localStorage 5-10MB 的配额上限；IndexedDB 配额通常是
 * "可用磁盘空间的一个百分比"，量级完全不同。代价是 API 变成异步的，
 * 消费方（StockDetailView）不能再像 localStorage 版本那样在 setup()
 * 里同步拿到值——但本地读取通常只要几毫秒，用户感知不到这个异步延迟。
 */

interface IntradayRecord {
  code: string
  data: Intraday
  /** 本地日期 YYYY-M-D，跨自然日直接判失效，不管 TTL 是否还剩余 */
  dateStr: string
  ts: number
}

interface KlineRecord {
  code: string
  period: Period
  kline: KlineItem[]
  indicators: Indicators
  ts: number
  /**
   * 全量后台同步（fullSync.ts）用：记录上次拿到这只股票 K线/指标时后端
   * 返回的 ETag，下次同步时带着当 If-None-Match 发回去，服务端没变就回
   * 304，不用重新传一遍 body。只有全量同步会写这两个字段——点开详情页时
   * 走的是普通请求（api/index.ts 的 getKline/getIndicators），不涉及
   * 条件请求，这两个字段就是 undefined，不影响正常展示/TTL 判断逻辑。
   */
  klineEtag?: string
  indicatorsEtag?: string
}

interface QuoteRecord {
  code: string
  data: Quote
  /** 本地日期 YYYY-M-D，跨自然日直接判失效，理由同分时图（今开/最高/最低是"今天"的） */
  dateStr: string
  ts: number
}

class ChartDiskCacheDB extends Dexie {
  intraday!: Table<IntradayRecord, string>
  kline!: Table<KlineRecord, [string, Period]>
  quote!: Table<QuoteRecord, string>
  /** 全量同步的断点续传状态，见 fullSync.ts；固定用字符串 key 存单条记录 */
  meta!: Table<{ key: string; value: unknown }, string>

  constructor() {
    super('stock_app_chart_cache')
    this.version(1).stores({
      intraday: 'code, ts',
      // 复合主键 [code+period]：一只股票的三个周期各是独立一条记录
      kline: '[code+period], ts'
    })
    // v2：新增 quote 表。Dexie 会自动把已存在的 v1 库升级到 v2（保留 intraday/kline
    // 原有数据，新增空的 quote 表），版本号必须严格递增，不能直接改 v1 的 stores()。
    this.version(2).stores({
      intraday: 'code, ts',
      kline: '[code+period], ts',
      quote: 'code, ts'
    })
    // v3：新增 meta 表，供 fullSync.ts 记录"今天有没有做完全量同步 / 断点续传
    // 到第几个了"。只加表不动已有表的 index 定义，老数据不受影响。
    this.version(3).stores({
      intraday: 'code, ts',
      kline: '[code+period], ts',
      quote: 'code, ts',
      meta: 'key'
    })
  }
}

const db = new ChartDiskCacheDB()

const TTL_MS = 24 * 60 * 60 * 1000
/** 30 天没再写入/刷新过的记录，判定为过期数据，清理释放空间——见 pruneStaleGlobally() */
const RETENTION_MS = 30 * 24 * 60 * 60 * 1000
/** 最多缓存这么多只股票的分时图，超过按最久没刷新的淘汰 */
const MAX_INTRADAY = 100
/**
 * 最多缓存这么多条 K线记录。上调到 900 是因为全量后台同步（fullSync.ts）
 * 会把整个股票池（目前 500 只）的日K都落到这张表——500 条是全量同步的
 * 硬需求，另外预留约 400 条给用户实际点开、看了周K/月K产生的记录
 * （旧的 300 上限是在只有"点过的股票才有缓存"这个前提下定的，全量同步
 * 把这个前提改变了，上限也得跟着调）。
 */
const MAX_KLINE = 900
/** 最多缓存这么多只股票的实时行情快照，超过按最久没刷新的淘汰 */
const MAX_QUOTE = 100

function todayStr(): string {
  const d = new Date()
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`
}

/** 按 ts 升序淘汰 intraday 表里最旧的记录，直到不超过 MAX_INTRADAY 条 */
async function evictOldestIntraday(): Promise<void> {
  const count = await db.intraday.count()
  if (count <= MAX_INTRADAY) return
  const staleKeys = await db.intraday.orderBy('ts').limit(count - MAX_INTRADAY).primaryKeys()
  await db.intraday.bulkDelete(staleKeys)
}

/** 按 ts 升序淘汰 kline 表里最旧的记录，直到不超过 MAX_KLINE 条 */
async function evictOldestKline(): Promise<void> {
  const count = await db.kline.count()
  if (count <= MAX_KLINE) return
  const staleKeys = await db.kline.orderBy('ts').limit(count - MAX_KLINE).primaryKeys()
  await db.kline.bulkDelete(staleKeys)
}

/** 按 ts 升序淘汰 quote 表里最旧的记录，直到不超过 MAX_QUOTE 条 */
async function evictOldestQuote(): Promise<void> {
  const count = await db.quote.count()
  if (count <= MAX_QUOTE) return
  const staleKeys = await db.quote.orderBy('ts').limit(count - MAX_QUOTE).primaryKeys()
  await db.quote.bulkDelete(staleKeys)
}

/** 读取磁盘缓存的分时图：过期/不存在/IndexedDB 不可用都返回 null，不影响主流程 */
export async function readIntradayDiskCache(code: string): Promise<Intraday | null> {
  try {
    const rec = await db.intraday.get(code)
    if (!rec) return null
    if (Date.now() - rec.ts > TTL_MS) return null
    if (rec.dateStr !== todayStr()) return null
    return rec.data
  } catch {
    return null // 隐私模式/浏览器限制导致 IndexedDB 不可用时静默降级
  }
}

/** 预取 / 正式请求成功后调用：把分时图落一份到磁盘 */
export async function writeIntradayDiskCache(code: string, data: Intraday): Promise<void> {
  try {
    await db.intraday.put({ code, data, dateStr: todayStr(), ts: Date.now() })
    await evictOldestIntraday()
  } catch { /* 静默：只是少了这层加速，不影响功能 */ }
}

/** 读取磁盘缓存的 K线 + 指标：过期/不存在/IndexedDB 不可用都返回 null */
export async function readKlineDiskCache(
  code: string, period: Period
): Promise<{ kline: KlineItem[]; indicators: Indicators } | null> {
  try {
    const rec = await db.kline.get([code, period])
    if (!rec) return null
    if (Date.now() - rec.ts > TTL_MS) return null
    return { kline: rec.kline, indicators: rec.indicators }
  } catch {
    return null
  }
}

/**
 * 读取磁盘缓存的 K线 + 指标，不做 TTL 过期判断——专供增量拉取
 * （klineIncremental.ts）用来确定"本地最新一根是哪天"，哪怕缓存已经
 * 放了好几天，作为增量合并的历史基础依然是正确的，只是需要问后端要
 * 更多新记录而已，跟"是否新鲜到可以直接展示"是两回事。
 */
export async function readKlineDiskCacheRaw(
  code: string, period: Period
): Promise<{ kline: KlineItem[]; indicators: Indicators } | null> {
  try {
    const rec = await db.kline.get([code, period])
    return rec ? { kline: rec.kline, indicators: rec.indicators } : null
  } catch {
    return null
  }
}

/** 预取 / 正式请求成功后调用：把 K线 + 指标落一份到磁盘 */
export async function writeKlineDiskCache(
  code: string, period: Period, kline: KlineItem[], indicators: Indicators
): Promise<void> {
  try {
    await db.kline.put({ code, period, kline, indicators, ts: Date.now() })
    await evictOldestKline()
  } catch { /* 静默 */ }
}

/** 读取磁盘缓存的实时行情快照：过期/不存在/IndexedDB 不可用都返回 null */
export async function readQuoteDiskCache(code: string): Promise<Quote | null> {
  try {
    const rec = await db.quote.get(code)
    if (!rec) return null
    if (Date.now() - rec.ts > TTL_MS) return null
    if (rec.dateStr !== todayStr()) return null
    return rec.data
  } catch {
    return null
  }
}

/** 请求成功后调用：把行情快照（价格/今开/最高/最低/成交量/成交额等）落一份到磁盘 */
export async function writeQuoteDiskCache(code: string, data: Quote): Promise<void> {
  try {
    await db.quote.put({ code, data, dateStr: todayStr(), ts: Date.now() })
    await evictOldestQuote()
  } catch { /* 静默 */ }
}

/**
 * 30 天没再写入/刷新过的记录清理——统一按各表已有的 ts（最后写入时间）
 * 字段判断，不新增"最后打开时间"这个概念，理由：
 * 1. 全量同步（fullSync.ts）例行跑的话，股票池里还在的股票 ts 会不断被
 *    刷新，30 天规则实际清理的主要是"已经从股票池下架/全量同步不再覆盖"
 *    的旧数据——不需要额外判断"股票是否退市"，自然过期即可，更简单。
 * 2. intraday/quote 这两类数据完全是"用户点开详情页时才产生"的缓存，
 *    ts 就是"最后一次被这只股票的详情页请求刷新的时间"，跟用户是否
 *    还在关注这只股票直接对应，符合"多久没打开清理"的直觉。
 *
 * 建议在 App 启动时调用一次（main.ts），不需要频繁调用——这不是一个
 * "读取路径"上的检查，是一次性的存储空间维护，放进 onIdle 里跑，
 * 不阻塞首屏。
 */
export async function pruneStaleGlobally(): Promise<void> {
  try {
    const cutoff = Date.now() - RETENTION_MS
    const [staleIntraday, staleKline, staleQuote] = await Promise.all([
      db.intraday.where('ts').below(cutoff).primaryKeys(),
      db.kline.where('ts').below(cutoff).primaryKeys(),
      db.quote.where('ts').below(cutoff).primaryKeys()
    ])
    await Promise.all([
      staleIntraday.length ? db.intraday.bulkDelete(staleIntraday) : Promise.resolve(),
      staleKline.length ? db.kline.bulkDelete(staleKline) : Promise.resolve(),
      staleQuote.length ? db.quote.bulkDelete(staleQuote) : Promise.resolve()
    ])
  } catch { /* 静默：清理失败不影响功能，顶多是多占了点空间 */ }
}

/** 全量同步进度读取，取不到/IndexedDB 不可用都返回 null（视为"没有进度，从头开始"） */
export async function getSyncMeta<T>(key: string): Promise<T | null> {
  try {
    const rec = await db.meta.get(key)
    return rec ? (rec.value as T) : null
  } catch {
    return null
  }
}

export async function setSyncMeta(key: string, value: unknown): Promise<void> {
  try {
    await db.meta.put({ key, value })
  } catch { /* 静默 */ }
}

/**
 * 全量同步专用的批量写入：一批（几十只股票）攒成一个数组，一次 bulkPut
 * 提交，而不是每只股票单独 put 一次。IndexedDB 的写事务有固定开销，
 * 500 只股票如果逐条 put，等于 500 次独立事务；bulkPut 把一批合并成
 * 一次事务，主线程占用时间大幅减少，也更符合"不阻塞主线程"的要求。
 * 与 writeKlineDiskCache（点开详情页时的单条写入）刻意分开成两个函数，
 * 是因为调用场景和"一次写几条"这个形状本来就不同，硬合并成一个反而
 * 两边都要兼容对方的参数形状，不值得。
 */
export async function bulkWriteKlineDiskCache(
  records: Array<{
    code: string
    period: Period
    kline: KlineItem[]
    indicators: Indicators
    klineEtag?: string
    indicatorsEtag?: string
  }>
): Promise<void> {
  if (records.length === 0) return
  try {
    const ts = Date.now()
    await db.kline.bulkPut(records.map(r => ({ ...r, ts })))
    await evictOldestKline()
  } catch { /* 静默 */ }
}

/**
 * 全量同步专用：服务端确认没变（304）时调用——只把 ts 刷新到当前时间，
 * 不碰 kline/indicators 内容。为什么需要这一步：如果 304 命中时完全不写
 * 任何东西，这条记录的 ts 会停留在很久以前第一次同步成功的那一刻，
 * 30 天保留规则（pruneStaleGlobally）就会误判它是"没人管的旧数据"给
 * 删掉——即使它其实每天都在被全量同步正常验证、内容一直是最新的。
 */
export async function touchKlineTs(codes: string[], period: Period): Promise<void> {
  if (codes.length === 0) return
  try {
    const now = Date.now()
    await db.transaction('rw', db.kline, async () => {
      for (const code of codes) {
        await db.kline.update([code, period], { ts: now })
      }
    })
  } catch { /* 静默 */ }
}

/** 全量同步读取某只股票已有的 ETag（用于下次条件请求时带上 If-None-Match），没有就返回 undefined */
export async function readKlineEtags(
  code: string, period: Period
): Promise<{ klineEtag?: string; indicatorsEtag?: string } | null> {
  try {
    const rec = await db.kline.get([code, period])
    return rec ? { klineEtag: rec.klineEtag, indicatorsEtag: rec.indicatorsEtag } : null
  } catch {
    return null
  }
}
