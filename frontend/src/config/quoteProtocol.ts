export type QuoteProtocol = 'json' | 'protobuf'

/**
 * 「行情 + 分时」组合数据用哪套协议获取，改这一个值就行，不用去改任何
 * 调用方代码——真正分流逻辑在 api/quoteIntradayGateway.ts。
 *
 * 'json'     - 现有 JSON+Gzip（走 getDetailBootstrap 单次组合请求），
 *              默认值，是经过验证的稳定路径。
 * 'protobuf' - Protobuf+Gzip POC（走 /quote-intraday.pb），实测体积明显
 *              更小（见 DebugCachePanel.vue 的对比面板），但还没经过
 *              大规模真实流量验证。出问题就把这个值改回 'json'，
 *              立刻整体回退，不需要再改别的地方。
 *
 * 只影响"quote 和 intraday 本来就要一起刷新"的场景（目前只有
 * StockDetailView.vue 的 onActivated 组合刷新），不影响 loadQuote() 那种
 * 只要 quote 的单独轮询，也不影响 quoteIntradaySync.ts 里 quote/intraday
 * 各自独立判断新鲜度的后台同步——具体原因见 quoteIntradayGateway.ts 顶部注释。
 */
export const QUOTE_PROTOCOL: QuoteProtocol = 'json'
