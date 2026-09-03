/**
 * 全局流量统计：区分"预取"和"总"两条口径的 API 流量，从 App 打开那一刻
 * 开始记，纯内存计数，刷新页面归零（不持久化——这是"这次打开 App 用了
 * 多少"，不是"设备上历史累计用了多少"）。
 *
 * 【v2：放弃 Resource Timing，改成直接量响应体】最初版本用浏览器的
 * Resource Timing API（`transferSize`）——理论上最准（是 gzip 压缩后、
 * 真正在网络上跑的字节数），但它有一个前提：跨域请求必须要响应带
 * `Timing-Allow-Origin` 头，否则浏览器直接把这个字段清零。这个项目前端
 * 后端刚好是跨域部署（Cloudflare Workers + Railway），加了这个响应头
 * 之后仍然读到 0——具体是部署没生效、还是手机端 WebView 对这个较新的
 * 浏览器 API 支持不完整，没有电脑环境没法继续排查。与其继续在一个依赖
 * 一堆外部条件（跨域头配置对不对、部署有没有生效、WebView 支不支持）的
 * 方案上纠缠，不如换一个不依赖任何浏览器权限/跨域配置的做法：直接在
 * 拿到响应体的地方，用 JS 量一下这段文本本身有多少字节——这件事不需要
 * 任何特殊权限（CORS 允许了这次请求，天然就允许读它的完整响应体，这是
 * CORS 工作的前提，不是额外权限），在任何浏览器、任何 WebView 环境下
 * 都是确定能拿到准确值的。
 *
 * 【这样测到的是什么，不是什么，说清楚】测到的是"响应体解压后的字节数"
 * （JSON 文本本身的大小），不是"gzip 压缩后、真正在网络上传输的字节数"——
 * 这两者不是一回事，压缩后通常明显更小（对这种字段名重复、数字为主的
 * JSON，gzip 常见能压到原大小的 20%~30%）。所以这里报的数字是"用得多的
 * 上限参考"，比真实流量偏大，但胜在任何环境都测得准、不用等 CORS/部署
 * 问题排查清楚——对调试"预取是不是发多了、发得对不对"这个目的来说足够用，
 * 面板里也会明确标注这一点，不冒充是精确的网络流量。
 *
 * 【谁负责调用】http.ts 的 request()（真实交互）和 fetchLowPriority()
 * （所有预取——viewportPrefetch/detailPrefetch/fullSync/quoteIntradaySync
 * 全部收敛到这一个函数）各自在拿到响应体之后调用 recordApiTraffic()，
 * 直接把"这是不是预取"和"这次响应体多大"一起报过来，不再需要像 v1 那样
 * 靠 URL 里塞标记参数、事后从一堆 Resource Timing 条目里猜——态度上更
 * "笨"（要在两个地方各加一行），但不再依赖任何可能出问题的中间层。
 *
 * 【为什么还保留"最先 import"的约定】这次改成主动上报以后，模块本身
 * 不再需要任何启动动作（没有 observer 要注册），但仍然建议 main.ts 把
 * 这行 import 放在最前面——万一以后又要加被动监听类的统计，不用重新
 * 折腾一遍 import 顺序；现在留着这个约定成本为零。
 */

export interface TrafficStats {
  /** 从 App 打开到现在，所有 API 响应体解压后字节数之和（不是压缩后的网络字节数，见文件头注释） */
  totalBytes: number
  /** 其中，预取（viewportPrefetch/detailPrefetch/fullSync/quoteIntradaySync）占的部分 */
  prefetchBytes: number
  /** 上面两个字节数对应的请求笔数 */
  totalCount: number
  prefetchCount: number
}

const stats: TrafficStats = { totalBytes: 0, prefetchBytes: 0, totalCount: 0, prefetchCount: 0 }

/** http.ts 在拿到每次 API 响应后调用；bytes 是响应体解压后的字节数，isPrefetch 是这次请求是否走的预取通道 */
export function recordApiTraffic(bytes: number, isPrefetch: boolean): void {
  stats.totalBytes += bytes
  stats.totalCount += 1
  if (isPrefetch) {
    stats.prefetchBytes += bytes
    stats.prefetchCount += 1
  }
}

/** 调试面板读取当前累计值；返回的是快照的浅拷贝，调用方不会意外改到内部状态 */
export function getTrafficStats(): TrafficStats {
  return { ...stats }
}
