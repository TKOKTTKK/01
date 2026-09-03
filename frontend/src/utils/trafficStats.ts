/**
 * 全局流量统计：区分"预取"和"总"两条口径的 API 流量，从 App 打开那一刻
 * 开始记，纯内存计数，刷新页面归零（不持久化——这是"这次打开 App 用了
 * 多少"，不是"设备上历史累计用了多少"，见需求讨论）。
 *
 * 【怎么测字节数】不用自己拦截 fetch/axios 算 body 大小（拿到的会是解压后
 * 的大小，跟"流量"的直觉——网络上真实传了多少字节——不是一回事）。用浏览器
 * 原生 Resource Timing API：每个 fetch/XHR 请求完成后，
 * `PerformanceResourceTiming.transferSize` 就是这次请求实际在网络上传输的
 * 字节数（已经是 gzip 压缩后的、含响应头开销的），命中协商缓存时为 0，
 * 精确对应"流量"这个词的直觉含义，浏览器免费提供，不用我们自己实现。
 *
 * 【怎么区分"预取"和"真实交互"】这两类请求在代码里已经天然收敛到两个不同
 * 的函数——预取相关的（viewportPrefetch/detailPrefetch/fullSync/
 * quoteIntradaySync）全部走 http.ts 的 fetchLowPriority，真实交互走
 * request()（axios）。Resource Timing 拿不到请求头/调用方信息，只能看
 * URL，所以在 fetchLowPriority 里给 URL 加了一个不影响后端处理的标记
 * 参数 `_pf=1`（后端接口都是 @PathVariable 绑定，未知的多余 query 参数会
 * 被直接忽略，不影响业务），本文件靠这个标记从 URL 里判断"这条请求是不是
 * 预取发出的"。
 *
 * 【为什么是"自启动"，import 一下就开始记，不用显式调用 start()】
 * 为了保证真的是"从打开 App 那一刻"开始记、不会因为漏调用而错过前面几个
 * 请求，把启动逻辑放在模块顶层（import 时就执行），并且要求 main.ts 把
 * 这一行 import 放在最前面（早于任何会触发请求的代码）。
 */

export interface TrafficStats {
  /** 从 App 打开到现在，所有 /api/ 请求的真实网络传输字节数之和 */
  totalBytes: number
  /** 其中，预取（viewportPrefetch/detailPrefetch/fullSync/quoteIntradaySync）占的部分 */
  prefetchBytes: number
  /** 上面两个字节数对应的请求笔数 */
  totalCount: number
  prefetchCount: number
}

const stats: TrafficStats = { totalBytes: 0, prefetchBytes: 0, totalCount: 0, prefetchCount: 0 }

function isApiEntry(name: string): boolean {
  return name.includes('/api/')
}

function isPrefetchEntry(name: string): boolean {
  return name.includes('_pf=1')
}

function ingest(entries: PerformanceResourceTiming[]): void {
  for (const entry of entries) {
    if (!isApiEntry(entry.name)) continue
    // transferSize 命中 HTTP 缓存（304/协商缓存）时为 0，不代表请求没发生，
    // 只是没占用额外流量——这里如实累加，0 就是 0，不用什么兜底估算值
    stats.totalBytes += entry.transferSize
    stats.totalCount += 1
    if (isPrefetchEntry(entry.name)) {
      stats.prefetchBytes += entry.transferSize
      stats.prefetchCount += 1
    }
  }
}

// PerformanceObserver 在极少数环境（很老的浏览器 / 部分 WebView）可能不存在，
// 静默跳过——流量统计只是调试辅助信息，不应该因为这个导致 App 白屏
try {
  if (typeof PerformanceObserver !== 'undefined') {
    const observer = new PerformanceObserver((list) => {
      ingest(list.getEntries() as PerformanceResourceTiming[])
    })
    // buffered: true 把 observer 创建之前就已经发生的请求也一并补上——
    // 万一 main.ts 里这行 import 因为某些原因没排到最前面，也不会整个错过
    observer.observe({ type: 'resource', buffered: true })

    // resource timing 缓冲区有上限（不同浏览器 150~250 条不等），长时间
    // 运行不清空会导致浏览器停止记录新条目；PerformanceObserver 本身不受
    // 这个缓冲区限制（是流式回调，不是从缓冲区里读），但为了不让缓冲区被
    // 打满影响其它可能用到 Resource Timing 的代码/浏览器插件，定期清空
    // 已经消费过的条目
    window.setInterval(() => {
      try { performance.clearResourceTimings() } catch { /* 静默 */ }
    }, 5 * 60 * 1000)
  }
} catch {
  // 静默：某些浏览器对 observe({ type: 'resource' }) 的支持有细微差异，
  // 失败就当没有这个统计能力，不影响 App 其它任何功能
}

/** 调试面板读取当前累计值；返回的是快照的浅拷贝，调用方不会意外改到内部状态 */
export function getTrafficStats(): TrafficStats {
  return { ...stats }
}
