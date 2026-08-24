import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getHotStocks, getMarketIndex, listStocks } from '@/api'
import type { MarketIndex, StockItem } from '@/api/types'
import { useUserStore } from './user'
import { useWatchlistStore } from './watchlist'

const CACHE_KEY = 'market_cache_v1'
const REFRESH_MS = 10000

/**
 * 全局行情 Store：首页/行情页/自选页共用，避免各页面重复请求。
 * Stale-While-Revalidate：启动时先展示本地缓存，后台刷新后局部更新。
 */
export const useMarketStore = defineStore('market', () => {
  const indexes = ref<MarketIndex[]>([])
  const stocks = ref<StockItem[]>([])
  const hot = ref<StockItem[]>([])
  const updatedAt = ref(0)
  const error = ref(false)
  let timer: number | undefined
  let inflight: Promise<void> | null = null

  // 启动即恢复缓存（秒开）
  try {
    const raw = localStorage.getItem(CACHE_KEY)
    if (raw) {
      const c = JSON.parse(raw)
      indexes.value = c.indexes || []
      stocks.value = c.stocks || []
      hot.value = c.hot || []
      updatedAt.value = c.updatedAt || 0
    }
  } catch { /* 缓存损坏则忽略 */ }

  async function refresh() {
    if (inflight) return inflight // 合并并发请求
    inflight = (async () => {
      try {
        const [idx, all, hotList] = await Promise.all([
          getMarketIndex(), listStocks(), getHotStocks(6)
        ])
        indexes.value = idx
        stocks.value = all
        hot.value = hotList
        updatedAt.value = Date.now()
        error.value = false
        localStorage.setItem(CACHE_KEY, JSON.stringify({
          indexes: idx, stocks: all, hot: hotList, updatedAt: updatedAt.value
        }))
        // 已登录时顺带刷新自选（同一轮询，不额外起定时器）
        const userStore = useUserStore()
        if (userStore.isLoggedIn()) {
          useWatchlistStore().refresh().catch(() => { /* 静默 */ })
        }
      } catch (e) {
        error.value = true
        console.error('行情刷新失败:', e)
      } finally {
        inflight = null
      }
    })()
    return inflight
  }

  /**
   * 页面从后台切回前台时立即刷新一次。
   * 否则用户锁屏几分钟后回来，要干等下一个 10 秒 tick 才更新，
   * 期间看到的是过期价格。
   */
  let visibilityBound = false
  function bindVisibility() {
    if (visibilityBound) return
    visibilityBound = true
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') refresh()
    })
  }

  /** 各页面进入时调用：有缓存立即可见，同时后台刷新并启动全局唯一轮询 */
  function ensure() {
    if (!updatedAt.value || Date.now() - updatedAt.value > 3000) refresh()
    bindVisibility()
    if (timer === undefined) {
      timer = window.setInterval(() => {
        if (document.visibilityState === 'visible') refresh()
      }, REFRESH_MS)
    }
  }

  return { indexes, stocks, hot, updatedAt, error, refresh, ensure }
})
