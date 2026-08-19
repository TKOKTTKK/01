/** 与后端 VO 对应的类型定义 */

export interface StockItem {
  id: number
  code: string
  name: string
  market: string
  industry: string | null
  price: number | null
  changeAmount: number | null
  changePercent: number | null
}

export interface Quote {
  code: string
  name: string
  price: number
  openPrice: number
  highPrice: number
  lowPrice: number
  preClose: number
  changeAmount: number
  changePercent: number
  volume: number
  amount: number
  tradeTime: string
  mock: boolean
}

export interface KlineItem {
  date: string
  open: number
  high: number
  low: number
  close: number
  volume: number
  amount: number
}

export interface IntradayPoint {
  time: string
  price: number
  avgPrice: number
  volume: number
  amount: number
}

export interface Intraday {
  code: string
  preClose: number
  high: number
  low: number
  points: IntradayPoint[]
  mock: boolean
}

export interface Indicators {
  dates: string[]
  ma: { ma5: (number | null)[]; ma10: (number | null)[]; ma20: (number | null)[]; ma60: (number | null)[] }
  macd: { dif: (number | null)[]; dea: (number | null)[]; macd: (number | null)[] }
  kdj: { k: (number | null)[]; d: (number | null)[]; j: (number | null)[] }
  rsi: { rsi6: (number | null)[]; rsi12: (number | null)[]; rsi24: (number | null)[] }
}

export interface MarketIndex {
  code: string
  name: string
  value: number
  changeAmount: number
  changePercent: number
}

export interface NewsItem {
  id: number
  stockCode: string | null
  title: string
  source: string
  url: string
  content: string
  publishTime: string
}

export interface UserInfo {
  id: number
  username: string
  createdAt: string
}

export interface TokenData {
  token: string
  user: UserInfo
}

export type Period = 'day' | 'week' | 'month'
