import { request } from './http'
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

export const getStock = (code: string) => request<StockItem>({ url: `/api/stocks/${code}` })

export const getQuote = (code: string) => request<Quote>({ url: `/api/stocks/${code}/quote` })

export const getIntraday = (code: string) =>
  request<Intraday>({ url: `/api/stocks/${code}/intraday` })

/** 详情页首屏聚合：一次请求拿到 stock + quote + intraday（冷启动专用） */
export const getDetailBootstrap = (code: string) =>
  request<DetailBootstrap>({ url: `/api/stocks/${code}/detail-bootstrap` })

/**
 * K线：传 limit 走全量/tail 语义；传 since（yyyy-MM-dd）则只返回该日期
 * 之后的新记录，用于本地已有历史缓存时的增量拉取（见 klineIncremental.ts）。
 * 两者互斥，since 优先——传了 since 时 limit 会被后端忽略。
 */
export const getKline = (code: string, period: Period, limit?: number, since?: string) =>
  request<KlineItem[]>({ url: `/api/stocks/${code}/kline`, params: { period, limit, since } })

export const getIndicators = (code: string, period: Period, limit?: number, since?: string) =>
  request<Indicators>({ url: `/api/stocks/${code}/indicators`, params: { period, limit, since } })

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
