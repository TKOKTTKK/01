import * as protobuf from 'protobufjs'
import protoSource from './proto/quoteIntraday.proto?raw'
import { toHHmm } from '@/utils/tradingMinuteOffset'
import type { Quote, Intraday, IntradayPoint } from './types'

/** 与后端 QuoteIntradayProtoMapper 的 SCALE 保持一致：真实值 * 100 存成定点整数 */
const SCALE = 100

/**
 * 用 protobufjs 的运行时动态解析（protobuf.parse），而不是 pbjs/pbts 预编译
 * 生成 .d.ts：这个项目目前的前端构建链路里没有 protoc 这类需要额外安装的
 * 本地工具，CI/沙箱环境很多时候也没有网络去装，parse() 只需要一个 npm
 * 依赖（protobufjs），运行时直接把 .proto 源文本解析成消息描述符，零额外
 * 构建步骤——用 Vite 内置支持的 `?raw` 后缀把 .proto 当纯文本导入即可。
 *
 * 代价：拿到的消息类型是运行时 protobuf.Type，TS 层面是 any，不像 codegen
 * 出来的类型那样有编译期字段名检查，容易手滑打错字段名而要等运行时才
 * 报错。如果这套协议以后接口数量变多、需要长期维护，建议换成上一轮回复
 * 里 pbjs/pbts 的 codegen 方案，用构建时间换回类型安全；现在先用动态解析
 * 把链路跑通、验证收益是否值得，是更快的起点。
 */
const root = protobuf.parse(protoSource, { keepCase: false }).root
const QuoteIntradayResponseType = root.lookupType('stockapp.market.QuoteIntradayResponse')

export interface QuoteIntradaySnapshot {
  quote: Quote
  intraday: Intraday
}

/** protobufjs 对 int64/sint64 字段默认解码成 long.js 的 Long 实例，不是普通 number；
 *  这里统一转换。本项目里所有定点数值（价格*100、成交量、成交额*100、epoch millis）
 *  量级都远小于 Number.MAX_SAFE_INTEGER（2^53），转换不会丢精度。 */
function toNum(v: unknown): number {
  if (v == null) return 0
  if (typeof v === 'number') return v
  const maybeLong = v as { toNumber?: () => number }
  return typeof maybeLong.toNumber === 'function' ? maybeLong.toNumber() : Number(v)
}

interface RawIntradayPointDelta {
  minuteOffset: number
  priceDelta: unknown
  avgPriceDelta: unknown
  volume: unknown
  amountDelta: unknown
}

interface RawQuoteIntradayVO {
  code: string
  name: string
  price: unknown
  preClose: unknown
  changeAmount: unknown
  changePercent: number
  volume: unknown
  amount: unknown
  tradeTime: unknown
  mock: boolean
  openPrice: unknown
  highPrice: unknown
  lowPrice: unknown
  firstPrice: unknown
  firstAvgPrice: unknown
  firstAmount: unknown
  intradayPreClose: unknown
  intradayHigh: unknown
  intradayLow: unknown
  points: RawIntradayPointDelta[]
}

/**
 * 请求 Protobuf + Gzip 版的「行情 + 分时」接口，解码并把差值链还原成正常
 * 数值，返回跟现有 getQuote()/getIntraday() 同形状的对象——上层 store/
 * 组件完全不用感知协议差异，方便先挑一两个页面接进去做灰度对比。
 */
export async function getQuoteIntradayProto(
  code: string,
  signal?: AbortSignal
): Promise<QuoteIntradaySnapshot> {
  const resp = await fetch(`/api/stocks/${code}/quote-intraday.pb`, {
    method: 'GET',
    // 只设置 Accept：告诉后端我们要的是 Protobuf 而不是 JSON。
    // 不要在这里手动加 'Accept-Encoding': 'gzip'——它是 Fetch 规范里的
    // "forbidden header name"，浏览器不允许 JS 代码设置（fetch/XHR 都一样），
    // 写了也会被浏览器静默丢弃，不会报错但也不会生效，容易误导人以为
    // 是这行代码在控制压缩协商。真实情况是：浏览器自己会在每个请求上
    // 自动带上它支持的编码列表（现代浏览器基本都有 gzip），后端
    // QuoteIntradayProtoController 就是读这个「浏览器自动带上」的头来
    // 决定要不要压缩，前端完全不用管。
    headers: { Accept: 'application/x-protobuf' },
    signal
  })
  if (!resp.ok) {
    throw new Error(`请求失败: HTTP ${resp.status}`)
  }

  // 关键点，容易搞反：这里不需要手动解压。
  // 只要响应头里有 Content-Encoding: gzip，浏览器网络层会在把 body 交给
  // JS 之前自动完成 gzip 解压——fetch().arrayBuffer() 拿到的已经是解压后的
  // 原始 Protobuf 字节；axios 场景下同理，设置 responseType: 'arraybuffer'
  // 拿到的 response.data 也已经是解压后的数据，都不需要额外引入 pako 之类
  // 的库去手动 gunzip。这一点在下面「重构注意事项」里会再展开，因为最容易
  // 被想当然地写成「前端也要解压一次」，导致重复解压报错或者干脆解不出来。
  const buffer = await resp.arrayBuffer()
  const decoded = QuoteIntradayResponseType.decode(new Uint8Array(buffer)) as unknown as {
    code: number
    message: string
    data?: RawQuoteIntradayVO
  }

  if (decoded.code !== 0) {
    throw new Error(decoded.message || '请求失败')
  }
  if (!decoded.data) {
    throw new Error('响应缺少 data 字段')
  }
  return toSnapshot(decoded.data)
}

function toSnapshot(d: RawQuoteIntradayVO): QuoteIntradaySnapshot {
  const quote: Quote = {
    code: d.code,
    name: d.name,
    price: toNum(d.price) / SCALE,
    openPrice: toNum(d.openPrice) / SCALE,
    highPrice: toNum(d.highPrice) / SCALE,
    lowPrice: toNum(d.lowPrice) / SCALE,
    preClose: toNum(d.preClose) / SCALE,
    changeAmount: toNum(d.changeAmount) / SCALE,
    changePercent: d.changePercent / SCALE,
    volume: toNum(d.volume),
    amount: toNum(d.amount) / SCALE,
    tradeTime: new Date(toNum(d.tradeTime)).toISOString(),
    mock: d.mock
  }

  // 差值链还原：points[0] 是绝对值起点（对应父消息的 first_* 字段），
  // 从 points[1] 开始每个点的绝对值 = 上一个点的绝对值 + 这个点的差值，
  // 做一次前缀和（累加）即可，跟后端编码时的逻辑完全对称。
  const points: IntradayPoint[] = []
  let cumPrice = toNum(d.firstPrice)
  let cumAvgPrice = toNum(d.firstAvgPrice)
  let cumAmount = toNum(d.firstAmount)

  d.points.forEach((p, i) => {
    if (i > 0) {
      cumPrice += toNum(p.priceDelta)
      cumAvgPrice += toNum(p.avgPriceDelta)
      cumAmount += toNum(p.amountDelta)
    }
    points.push({
      time: toHHmm(p.minuteOffset),
      price: cumPrice / SCALE,
      avgPrice: cumAvgPrice / SCALE,
      volume: toNum(p.volume),
      amount: cumAmount / SCALE
    })
  })

  const intraday: Intraday = {
    code: d.code,
    preClose: toNum(d.intradayPreClose) / SCALE,
    high: toNum(d.intradayHigh) / SCALE,
    low: toNum(d.intradayLow) / SCALE,
    points,
    mock: d.mock
  }

  return { quote, intraday }
}
