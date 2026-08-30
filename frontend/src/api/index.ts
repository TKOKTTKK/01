import { request, requestConditional, requestLowPriority } from './http'
import type {
  DetailBootstrap, Indicators, Intraday, KlineItem, MarketIndex,
  NewsItem, PageResult, Period, Quote, SimAccount, SimCashFlow, SimPosition,
  SimPortfolio, SimTrade, StockItem, TokenData, UserInfo
} from './types'

// ---------- 股票 / 行情 ----------
export const searchStocks = (keyword: string) =>
  request<StockItem[]>({ url: '/api/stocks/search', params: { keyword } })

/** 分页股票列表：page 从 1 开始。股票池扩大后前端改成滚动加载，不再一次性拉全部 */
export const listStocks = (page = 1, size = 50) =>
  request<PageResult<StockItem>>({ url: '/api/stocks', params: { page, size } })

/**
 * lowPriority：仅供预取场景传 true——改走原生 fetch + Fetch Priority('low')
 * 发出（见 http.ts 的 requestLowPriority）。好网络下预取请求不再被真实点击
 * 取消（见 detailPrefetch.ts），靠这个字段让浏览器网络栈自己在调度时把
 * 真实点击的请求排得靠前，而不是粗暴地互相取消。真实页面加载的调用不传
 * 这个参数，走原来的 axios 路径，无行为变化。
 */
export const getStock = (code: string, signal?: AbortSignal, lowPriority?: boolean) => {
  const cfg = { url: `/api/stocks/${code}`, signal }
  return lowPriority ? requestLowPriority<StockItem>(cfg) : request<StockItem>(cfg)
}

export const getQuote = (code: string, signal?: AbortSignal, lowPriority?: boolean) => {
  const cfg = { url: `/api/stocks/${code}/quote`, signal }
  return lowPriority ? requestLowPriority<Quote>(cfg) : request<Quote>(cfg)
}

/**
 * 批量取实时行情：可视区高频轮询专用（utils/visiblePricePolling.ts）——
 * 一次请求拿完当前屏幕可见的全部股票最新价格，不是逐只轮询。走正常优先级
 * （不是 requestLowPriority）——这是用户此刻正盯着看的股票，不是投机性
 * 预取，理应和真实点击享受同等的调度优先级。
 */
export const getQuotesBatch = (codes: string[]) =>
  request<Quote[]>({ url: '/api/stocks/quotes', params: { codes: codes.join(',') } })

export const getIntraday = (code: string, signal?: AbortSignal, lowPriority?: boolean) => {
  const cfg = { url: `/api/stocks/${code}/intraday`, signal }
  return lowPriority ? requestLowPriority<Intraday>(cfg) : request<Intraday>(cfg)
}

/** 详情页首屏聚合：一次请求拿到 stock + quote + intraday（冷启动专用） */
export const getDetailBootstrap = (code: string, signal?: AbortSignal, lowPriority?: boolean) => {
  const cfg = { url: `/api/stocks/${code}/detail-bootstrap`, signal }
  return lowPriority ? requestLowPriority<DetailBootstrap>(cfg) : request<DetailBootstrap>(cfg)
}

/**
 * K线：传 limit 走全量/tail 语义；传 since（yyyy-MM-dd）则只返回该日期
 * 之后的新记录，用于本地已有历史缓存时的增量拉取（见 klineIncremental.ts）。
 * 两者互斥，since 优先——传了 since 时 limit 会被后端忽略。
 */
export const getKline = (code: string, period: Period, limit?: number, since?: string, signal?: AbortSignal, lowPriority?: boolean) => {
  const cfg = { url: `/api/stocks/${code}/kline`, params: { period, limit, since }, signal }
  return lowPriority ? requestLowPriority<KlineItem[]>(cfg) : request<KlineItem[]>(cfg)
}

export const getIndicators = (code: string, period: Period, limit?: number, since?: string, signal?: AbortSignal, lowPriority?: boolean) => {
  const cfg = { url: `/api/stocks/${code}/indicators`, params: { period, limit, since }, signal }
  return lowPriority ? requestLowPriority<Indicators>(cfg) : request<Indicators>(cfg)
}

/**
 * 全量后台同步专用（fullSync.ts）：带条件请求的 K线/指标获取，本地有
 * ETag 就带上，服务端没变直接回 { notModified: true }，不产生 body 传输。
 * 跟上面 getKline/getIndicators 分开成独立函数，是因为返回类型完全不同
 * （ConditionalResult 而不是直接的数据），混进同一个函数会让所有原有
 * 调用方都要多判断一层 notModified，没必要。
 */
export const getKlineConditional = (code: string, period: Period, etag?: string, signal?: AbortSignal) =>
  requestConditional<KlineItem[]>({ url: `/api/stocks/${code}/kline`, params: { period }, etag, signal })

export const getIndicatorsConditional = (code: string, period: Period, etag?: string, signal?: AbortSignal) =>
  requestConditional<Indicators>({ url: `/api/stocks/${code}/indicators`, params: { period }, etag, signal })

export const getStockNews = (code: string, limit = 20) =>
  request<NewsItem[]>({ url: `/api/stocks/${code}/news`, params: { limit } })

export const getNewsDetail = (id: number | string) =>
  request<NewsItem>({ url: `/api/news/${id}` })

// ---------- 市场 ----------
export const getMarketIndex = () => request<MarketIndex[]>({ url: '/api/market/index' })

export const getHotStocks = (limit = 10) =>
  request<StockItem[]>({ url: '/api/market/hot', params: { limit } })

// ---------- 用户 ----------
export const apiRegister = (username: string, password: string) =>
  request<UserInfo>({ url: '/api/auth/register', method: 'post', data: { username, password } })

export const apiLogin = (username: string, password: string) =>
  request<TokenData>({ url: '/api/auth/login', method: 'post', data: { username, password } })

export const getProfile = () => request<UserInfo>({ url: '/api/user/profile' })

// ---------- 自选 ----------
export const getWatchlist = () => request<StockItem[]>({ url: '/api/watchlist' })

export const addWatch = (stockId: number) =>
  request<void>({ url: `/api/watchlist/${stockId}`, method: 'post' })

export const removeWatch = (stockId: number) =>
  request<void>({ url: `/api/watchlist/${stockId}`, method: 'delete' })

export const inWatchlist = (stockId: number) =>
  request<boolean>({ url: `/api/watchlist/contains/${stockId}` })

// ---------- 模拟交易 ----------
/** 账户 + 持仓合并快照：一次请求，总资产与持仓明细严格自洽 */
export const getSimPortfolio = () => request<SimPortfolio>({ url: '/api/sim/portfolio' })

export const getSimAccount = () => request<SimAccount>({ url: '/api/sim/account' })

export const getSimPositions = () => request<SimPosition[]>({ url: '/api/sim/positions' })

export const placeSimOrder = (code: string, side: 'BUY' | 'SELL', quantity: number) =>
  request<SimTrade>({ url: '/api/sim/order', method: 'post', data: { code, side, quantity } })

export const getSimTrades = (limit = 50) =>
  request<SimTrade[]>({ url: '/api/sim/trades', params: { limit } })

export const getSimCashFlows = (limit = 50) =>
  request<SimCashFlow[]>({ url: '/api/sim/cashflows', params: { limit } })
