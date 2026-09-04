import { QUOTE_PROTOCOL } from '@/config/quoteProtocol'
import { getDetailBootstrap } from './index'
import { getQuoteIntradayProto } from './protoQuoteIntraday'
import type { Quote, Intraday } from './types'

export interface QuoteIntradayResult {
  quote: Quote
  intraday: Intraday
}

/**
 * 「行情 + 分时」组合刷新的统一入口，按 config/quoteProtocol.ts 里的
 * QUOTE_PROTOCOL 分流到 JSON+Gzip（getDetailBootstrap）或
 * Protobuf+Gzip（getQuoteIntradayProto）。调用方拿到的是同一个形状的
 * 数据（{ quote, intraday }），不需要关心底层走的是哪套协议——两条分支
 * 请求失败时都会直接把异常抛给调用方，降级逻辑（比如"失败了就退回两个
 * 独立请求"）留给调用方按自己的场景决定要不要做，这里不吞异常。
 *
 * 【只用在这一个场景，不要图省事到处替换】
 * 只应该用在"quote 和 intraday 本来就要一起、无条件刷新"的地方——目前
 * 只有 StockDetailView.vue 的 onActivated 组合刷新符合这个特征。
 * 不要拿去替换：
 * - loadQuote()：只轮询 quote，不需要 intraday。Protobuf 接口把两者
 *   绑死在一起返回，用在这种"其实只要一个"的场景上是净增加流量，
 *   跟这次改造"省流量"的初衷正好相反。
 * - quoteIntradaySync.ts 的 syncOne()：quote 和 intraday 是各自独立判断
 *   本地缓存新鲜度、按需分别请求的（见该文件注释），一旦换成这个函数，
 *   哪怕只有一个过期也会被迫把两个都重新拉一遍，是真实的行为倒退，
 *   不是简单的协议替换。
 * - 冷启动路径（StockDetailView.vue 的 onMounted，实际调用链在
 *   detailPrefetch.ts 里）：牵扯预取缓存、viewport 命中判断等更复杂的
 *   调度逻辑，还没有对应的 Protobuf 版本，贸然接入需要先在
 *   detailPrefetch.ts 那一层单独设计，不是这个函数能覆盖的。
 */
export async function fetchQuoteIntraday(
  code: string,
  signal?: AbortSignal
): Promise<QuoteIntradayResult> {
  if (QUOTE_PROTOCOL === 'protobuf') {
    const { quote, intraday } = await getQuoteIntradayProto(code, signal)
    return { quote, intraday }
  }
  const bootstrap = await getDetailBootstrap(code, signal)
  return { quote: bootstrap.quote, intraday: bootstrap.intraday }
}
