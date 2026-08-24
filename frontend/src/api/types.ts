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

/** 详情页首屏聚合数据（GET /api/stocks/{code}/detail-bootstrap） */
export interface DetailBootstrap {
  stock: StockItem
  quote: Quote
  intraday: Intraday
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

// ---------- 模拟交易 ----------

export interface SimAccount {
  totalAssets: number
  availableCash: number
  frozenCash: number
  positionMarketValue: number
  todayProfit: number
  totalProfit: number
  totalProfitRate: number
  initialCash: number
  mock: boolean
}

export interface SimPosition {
  stockId: number
  code: string
  name: string
  quantity: number
  availableQuantity: number
  avgCost: number
  price: number | null
  marketValue: number | null
  profit: number | null
  profitRate: number | null
  todayProfit: number | null
}

export interface SimTrade {
  id: number
  code: string
  name: string
  side: 'BUY' | 'SELL'
  quantity: number
  price: number
  amount: number
  createdAt: string
}

export interface SimCashFlow {
  id: number
  type: string
  amount: number
  balance: number
  description: string
  createdAt: string
}

export interface SimPortfolio {
  account: SimAccount
  positions: SimPosition[]
}
