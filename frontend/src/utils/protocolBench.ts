/**
 * 用浏览器 Resource Timing API 量出一次请求「真正在网络上传输了多少字节、
 * 花了多久」，给 DebugCachePanel.vue 的灰度对比面板用。
 *
 * 为什么不能用 response.arrayBuffer().byteLength 或者手动读 Content-Length：
 * 那个数字是解压之后的字节数（body 本身有多大），不是压缩后实际在网络上
 * 跑的字节数——而我们要对比的恰恰是"Gzip/Protobuf 到底帮我们省了多少
 * 传输量"，必须拿 encodedBodySize（压缩后的字节数），这个数字只有
 * PerformanceResourceTiming 这个条目里有，Fetch/XHR 的 API 本身不暴露。
 *
 * 前提：跨域场景下这些体积字段默认清零，需要后端在响应上带
 * Timing-Allow-Origin（本项目已经在 TimingAllowOriginConfig.java 里加了，
 * 见该文件注释）；同源场景下不需要这个头也能正常拿到。
 */

export interface TransferMeasurement {
  url: string
  /** 解压后的 body 字节数（等于 arrayBuffer 的实际大小） */
  decodedBytes: number
  /** 实际在网络上传输的字节数（gzip 压缩后）。拿不到 Resource Timing 数据时
   *  退化成跟 decodedBytes 相同——这种情况下"压缩比"会显示成 1.0，
   *  是明确的"测不到"信号，而不是悄悄给一个错的数字。 */
  encodedBytes: number
  /** 是否成功拿到了 Resource Timing 条目（true 时 encodedBytes 才是真实值） */
  measured: boolean
  /** 这一次请求自己的耗时（不含同一批里并发的其他请求） */
  durationMs: number
  buffer: ArrayBuffer
}

/**
 * 浏览器默认的 Resource Timing 缓冲区只保留最近 ~250 条记录（Chrome 默认值，
 * 规范没强制具体数字，但普遍实现都有一个不算大的默认上限），满了之后
 * **新条目会被直接丢弃、不会覆盖旧的**，要显式调大才行。
 *
 * 这在 Vite 开发模式下特别容易踩到：Vite dev server 不打包，每个 import
 * 的模块都是单独一次网络请求，随便跑一会儿开发服务器、切几个页面，
 * 轻轻松松就能攒够 250 条模块加载记录，把缓冲区占满——等你真正想测的
 * 那几次 fetch 发出去的时候，Resource Timing 早就"记不下新条目"了，
 * 表现出来就是 measureRequest() 怎么测都拿不到真实的 encodedBodySize，
 * 100% 回退到"解压后大小"，而且是系统性地每次都测不到，不是偶发。
 *
 * 在这个模块被第一次 import 时就把缓冲区调大，越早执行越好——最好是
 * 页面刚加载、还没来得及塞满 250 条的时候就生效。
 */
if (typeof performance !== 'undefined' && typeof performance.setResourceTimingBufferSize === 'function') {
  performance.setResourceTimingBufferSize(2000)
}

let seq = 0
let loggedMissOnce = false

/**
 * 发起一次 GET 请求并测量。会在 URL 后面加一个自增的 `_dbg` 查询参数：
 * 1. 配合 cache: 'no-store' 确保每次都是真实网络请求，不会因为命中浏览器
 *    HTTP 缓存而拿到 transferSize=0（协议规定缓存命中就是 0，会被
 *    误读成"压缩后 0 字节"这种荒谬结论）。
 * 2. 让每次请求的 URL 都不同，方便按 URL 精确匹配到这一次对应的
 *    Resource Timing 条目，不会跟同一个 code 之前测过的请求混在一起。
 */
export async function measureRequest(
  url: string,
  init?: RequestInit
): Promise<TransferMeasurement> {
  const bench = ++seq
  const taggedUrl = url + (url.includes('?') ? '&' : '?') + `_dbg=${bench}`

  const start = performance.now()
  const resp = await fetch(taggedUrl, { ...init, cache: 'no-store' })
  if (!resp.ok) {
    throw new Error(`HTTP ${resp.status}`)
  }
  const buffer = await resp.arrayBuffer()
  const durationMs = performance.now() - start

  const decodedBytes = buffer.byteLength
  let encodedBytes = decodedBytes
  let measured = false

  // Resource Timing 条目是异步写入 Performance 缓冲区的，理论上 fetch 的
  // Promise resolve 时条目未必已经写进去；轮询几次（每次隔一个宏任务），
  // 比只等一次更抗偶发的时序错位，代价很小（最多多等几十毫秒）。
  let entry: PerformanceResourceTiming | undefined
  for (let i = 0; i < 5 && !entry; i++) {
    if (i > 0) await new Promise((r) => setTimeout(r, 20))
    entry = performance
      .getEntriesByType('resource')
      .filter((e) => e.name.endsWith(taggedUrl) || e.name === new URL(taggedUrl, location.href).href)
      .pop() as PerformanceResourceTiming | undefined
  }

  if (entry && entry.encodedBodySize > 0) {
    encodedBytes = entry.encodedBodySize
    measured = true
  } else if (!loggedMissOnce) {
    // 只打一次，避免连续测多次的时候刷屏；这条日志本身就是排障入口——
    // 如果 totalEntries 已经顶到几千（或者你手动调小过 setResourceTimingBufferSize），
    // 基本可以确认是缓冲区问题；如果 entry 是 undefined 但总数不多，
    // 大概率是 URL 匹配没对上或者时序问题，把 taggedUrl 和 entry?.name
    // 都打出来方便对比。
    loggedMissOnce = true
    console.warn(
      '[protocolBench] 没能从 Resource Timing 里测到真实传输字节数，已回退成解压后大小。' +
      '排障信息：',
      { taggedUrl, entryFound: !!entry, entryEncodedBodySize: entry?.encodedBodySize, totalResourceEntries: performance.getEntriesByType('resource').length }
    )
  }

  return { url, decodedBytes, encodedBytes, measured, durationMs, buffer }
}
