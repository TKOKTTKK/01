import type { Intraday } from '@/api/types'

/**
 * 分时图磁盘缓存：把预取/请求到的分时数据落一份到 localStorage，
 * 下次打开详情页可以在网络请求返回之前，同步、瞬时地把图先画出来
 * （0ms 可感知延迟——IntradayChart 挂载那一刻 props.data 已经有值，
 * 直接画真实曲线，不用先走 renderEmpty() 骨架），网络返回后再静默
 * 覆盖成最新数据，用户完全无感。
 *
 * 【为什么不是单纯按 24 小时倒计时】分时图是"今天"的数据。如果只按
 * 24 小时算，晚上缓存的会跨过零点、次日开盘前都还没过期，用户次日
 * 一早打开看到的会是"昨天全天"的分时图——形状、价格区间都是错的，
 * 比白屏更容易让人误以为这就是当下的价格。所以过期规则是「超过 24
 * 小时」或者「不是同一个自然日」，两个条件任一满足就失效，实际效果
 * 接近"当天有效，最长 24 小时"。
 *
 * 【为什么用 localStorage 而不是 IndexedDB】分时数据体量很小（一天
 * 241 个点，JSON 也就几 KB），localStorage 的同步读写在这个量级上
 * 不会造成卡顿；跟本项目 market.ts 已经在用的持久化方式保持一致，
 * 不引入新的存储机制。MAX_ENTRIES 做上限，防止股票池扩大后（几百/
 * 几千只）越攒越多，超出 localStorage 配额（通常单域名 5-10MB）。
 */

const STORAGE_KEY = 'intraday_disk_cache_v1'
const TTL_MS = 24 * 60 * 60 * 1000
/** 最多缓存这么多只股票的分时图，超过按最久没刷新的淘汰（近似 LRU） */
const MAX_ENTRIES = 50

interface DiskEntry {
  data: Intraday
  /** 本地日期 YYYY-M-D，跨自然日直接判失效，不管 TTL 是否还剩余 */
  dateStr: string
  ts: number
}

type Store = Record<string, DiskEntry>

function todayStr(): string {
  const d = new Date()
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`
}

function readStore(): Store {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as Store) : {}
  } catch {
    return {} // 解析失败（数据损坏/隐私模式）视为无缓存，不影响功能
  }
}

function writeStore(store: Store): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(store))
  } catch {
    /* 配额满或隐私模式禁用了 localStorage，静默放弃——只是少了这层加速，不影响主流程 */
  }
}

/** 读取磁盘缓存的分时图：过期或不存在返回 null，同步执行、零网络等待 */
export function readIntradayDiskCache(code: string): Intraday | null {
  const entry = readStore()[code]
  if (!entry) return null
  if (Date.now() - entry.ts > TTL_MS) return null
  if (entry.dateStr !== todayStr()) return null
  return entry.data
}

/** 预取 / 正式请求成功后调用：把结果落一份到磁盘，供下次「0ms 打开」用 */
export function writeIntradayDiskCache(code: string, data: Intraday): void {
  const store = readStore()
  store[code] = { data, dateStr: todayStr(), ts: Date.now() }

  const codes = Object.keys(store)
  if (codes.length > MAX_ENTRIES) {
    // 按写入时间淘汰最旧的，直到不超过上限。同一只股票被反复预取/访问会
    // 刷新自己的 ts，足够贴近"最久没被用到的先淘汰"这个真实访问顺序
    codes.sort((a, b) => store[a].ts - store[b].ts)
    for (const c of codes.slice(0, codes.length - MAX_ENTRIES)) {
      delete store[c]
    }
  }
  writeStore(store)
}
