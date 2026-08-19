import { request } from './http'
import type {
  Indicators, Intraday, KlineItem, MarketIndex,
  NewsItem, Period, Quote, StockItem, TokenData, UserInfo
} from './types'

// ---------- 股票 / 行情 ----------
export const searchStocks = (keyword: string) =>
  request<StockItem[]>({ url: '/api/stocks/search', params: { keyword } })

export const listStocks = () => request<StockItem[]>({ url: '/api/stocks' })

export const getStock = (code: string) => request<StockItem>({ url: `/api/stocks/${code}` })

export const getQuote = (code: string) => request<Quote>({ url: `/api/stocks/${code}/quote` })

export const getIntraday = (code: string) =>
  request<Intraday>({ url: `/api/stocks/${code}/intraday` })

export const getKline = (code: string, period: Period, limit = 250) =>
  request<KlineItem[]>({ url: `/api/stocks/${code}/kline`, params: { period, limit } })

export const getIndicators = (code: string, period: Period, limit = 250) =>
  request<Indicators>({ url: `/api/stocks/${code}/indicators`, params: { period, limit } })

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
