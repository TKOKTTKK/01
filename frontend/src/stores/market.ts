import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getHotStocks, getMarketIndex, listStocks } from '@/api'
import type { MarketIndex, StockItem } from '@/api/types'
import { isTradingHours } from '@/utils/tradingHours'
import { useUserStore } from './user'
import { useWatchlistStore } from './watchlist'

const CACHE_KEY = 'market_cache_v1'
const REFRESH_MS = 10000
/** 「全部股票」每页条数，需跟 VirtualStockList 的滚动加载步长一致 */
const STOCKS_PAGE_SIZE = 50

/**
 * 全局行情 Store：首页/行情页/自选页共用，避免各页面重复请求。
 * Stale-While-Revalidate：启动时先展示本地缓存，后台刷新后局部更新。
 *
 * 【v2 分页化】stocks 不再是"全部股票"，而是"目前已经滚动加载出来的
 * 前若干页"——股票池扩到几千只后，不管是一次性拉全量列表还是每 10 秒
 * 轮询全量，都会随股票总数线性变慢。现在只保证"用户已经看到的部分"
 * 保持新鲜：10 秒刷新只重新拉取 stocksPage 页数以内的数据，未滚动到的
 * 股票既不取也不占内存/DOM，跟股票总数彻底解耦。
 */
export const useMarketStore = defineStore('market', () => {
  const indexes = ref<MarketIndex[]>([])
  const stocks = ref<StockItem[]>([])
  const stocksPage = ref(1)
  const stocksTotal = ref(0)
  const stocksLoadingMore = ref(false)
  const hot = ref<StockItem[]>([])
  const updatedAt = ref(0)
  const error = ref(false)
  let timer: number | undefined
  let inflight: Promise<void> | null = null

  const stocksHasMore = computed(() => stocks.value.length < stocksTotal.value)

  // 启动即恢复缓存（秒开）
  try {
    const raw = localStorage.getItem(CACHE_KEY)
    if (raw) {
      const c = JSON.parse(raw)
      indexes.value = c.indexes || []
      stocks.value = c.stocks || []
      stocksPage.value = c.stocksPage || 1
      stocksTotal.value = c.stocksTotal || 0
      hot.value = c.hot || []
      updatedAt.value = c.updatedAt || 0
    }
  } catch { /* 缓存损坏则忽略 */ }

  /** 重新拉取「已经加载出来的那几页」，用于首次进入和每次定时刷新 */
  async function fetchLoadedStockPages() {
    const pageCount = stocksPage.value
    const pages = await Promise.all(
      Array.from({ length: pageCount }, (_, i) => listStocks(i + 1, STOCKS_PAGE_SIZE))
    )
    stocks.value = pages.flatMap(p => p.list)
    stocksTotal.value = pages[pages.length - 1]?.total ?? stocksTotal.value
  }

  /** 触底加载下一页，供 VirtualStockList 的 load-more 事件调用 */
  async function loadMoreStocks() {
    if (stocksLoadingMore.value || !stocksHasMore.value) return
    stocksLoadingMore.value = true
    try {
      const next = stocksPage.value + 1
      const res = await listStocks(next, STOCKS_PAGE_SIZE)
      stocks.value = [...stocks.value, ...res.list]
      stocksTotal.value = res.total
      stocksPage.value = next
    } catch { /* 静默，用户再次触底会重试 */ } finally {
      stocksLoadingMore.value = false
    }
  }

  async function refresh() {
    if (inflight) return inflight // 合并并发请求
    inflight = (async () => {
      try {
        const [idx, , hotList] = await Promise.all([
          getMarketIndex(), fetchLoadedStockPages(), getHotStocks(6)
        ])
        indexes.value = idx
        hot.value = hotList
        updatedAt.value = Date.now()
        error.value = false
        localStorage.setItem(CACHE_KEY, JSON.stringify({
          indexes: idx, stocks: stocks.value, stocksPage: stocksPage.value,
          stocksTotal: stocksTotal.value, hot: hotList, updatedAt: updatedAt.value
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
   * 期间看到的是过期价格。这一次刷新不受交易时段限制——用户主动切回来，
   * 不管是不是交易时段都该立刻看到最新状态（哪怕是"收盘了，价格没变"）。
   */
  let visibilityBound = false
  function bindVisibility() {
    if (visibilityBound) return
    visibilityBound = true
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') refresh()
    })
  }

  /**
   * 各页面进入时调用：有缓存立即可见，同时后台刷新并启动全局唯一轮询。
   *
   * 【交易时段判断】首次进入的这次 refresh() 不受限制——用户刚打开页面，
   * 不管是不是交易时段都该看到当前状态。真正按交易时段收紧的是下面
   * setInterval 里的定时刷新：非交易时段价格根本不会变，10 秒刷一次没有
   * 意义，白白消耗电量和网络；收盘后想看最新状态，切后台再切回来
   * （上面的 visibilitychange）或重新进页面依然会刷新一次，不影响体验。
   */
  function ensure() {
    if (!updatedAt.value || Date.now() - updatedAt.value > 3000) refresh()
    bindVisibility()
    if (timer === undefined) {
      timer = window.setInterval(() => {
        if (document.visibilityState === 'visible' && isTradingHours()) refresh()
      }, REFRESH_MS)
    }
  }

  return {
    indexes, stocks, stocksHasMore, stocksLoadingMore, hot, updatedAt, error,
    refresh, ensure, loadMoreStocks
  }
})
