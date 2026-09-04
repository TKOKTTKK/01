# Protobuf + Gzip 重构注意事项

本文档配合以下新增代码一起看：

- `backend/stock-common/src/main/proto/quote_intraday.proto` —— schema
- `backend/stock-api/.../proto/QuoteIntradayProtoMapper.java` —— VO → Protobuf（定点数 + 差值编码）
- `backend/stock-api/.../controller/QuoteIntradayProtoController.java` —— 手动 Gzip 响应
- `frontend/src/api/protoQuoteIntraday.ts` —— 请求 + 动态解析 + 差值还原
- `frontend/src/utils/tradingMinuteOffset.ts` / 后端 `TradingMinuteOffset.java` —— 分钟偏移换算

这是一个**并存的 POC 接口**（`/api/stocks/{code}/quote-intraday.pb`），原有的
`/quote`、`/intraday` JSON 接口原封不动保留，方便对比和灰度切换，不是替换式重构。

---

## 1. 内存

**后端**

- `QuoteIntradayProtoController.gzip()` 用 `ByteArrayOutputStream` 把整段
  压缩结果先攒在堆内存里，再一次性写回 `HttpServletResponse`。单只股票的
  行情 + 240 个分时点，压缩前几 KB、压缩后更小，这个量级完全没问题。
  **但如果以后要扩展成"全市场批量快照"（比如几千只股票一次性返回）**，
  就不能再这样一把梭：几千只 × 几 KB 会在每个并发请求上都额外占用几十
  MB 堆内存，高并发下容易把老年代打满触发 Full GC。真要做批量接口，
  应该把 `GZIPOutputStream` 直接包在 `response.getOutputStream()` 外层，
  边序列化边流式写出，不在 JVM 堆里攒完整的中间字节数组。
- `QuoteIntradayProtoMapper` 里每个字段都会产生一次 `BigDecimal.multiply()`
  的临时对象，单次请求量级可忽略；如果这个接口以后要支撑高频轮询（比如
  项目里已有的 `visiblePricePolling.ts` 那种秒级轮询），建议把"序列化后的
  gzip 字节"整体存进 Redis（复用现有 `RedisCacheHelper`），而不是每次请求
  都重新走一遍 VO→Proto→Gzip，用带 TTL 的缓存字节数组替代重复计算。

**前端**

- `resp.arrayBuffer()` 会把整个响应体一次性读进内存再返回，对单只股票的
  分时数据没有问题；如果以后做"批量预取多只股票"的二进制接口（类比现有
  `getDetailBootstrapBatch`），要注意 `ArrayBuffer` 用完之后没有主动释放的
  API（靠 GC），长列表快速切换时如果每次都产生新的大 ArrayBuffer 又不及时
  丢弃引用（比如意外存进了某个 Map 缓存里一直不清），会有内存堆积风险。
- `protobuf.parse()` 解析 `.proto` 源码、构建消息描述符这一步有一次性开销，
  代码里在模块顶层执行一次（`const root = protobuf.parse(...)`），只在
  这个模块第一次被 import 时跑一次，不要放进请求函数内部每次重新 parse。

---

## 2. 解压

这是最容易搞反的一块，务必按下面的心智模型理解：

- **浏览器环境下（本项目的 Vue 前端）不需要手动解压。** 只要后端响应头
  正确带了 `Content-Encoding: gzip`，`fetch()`/`XMLHttpRequest` 在把
  response body 交给 JS 代码之前，浏览器网络层已经自动完成了 gzip 解压。
  `protoQuoteIntraday.ts` 里 `resp.arrayBuffer()` 拿到的已经是解压后的
  原始 Protobuf 字节，直接喂给 `QuoteIntradayResponseType.decode()` 即可。
  **不要再引入 `pako` 之类的库在前端手动 gunzip 一次**——那样反而会因为
  数据已经被浏览器解压过、你再拿"已解压的字节"去尝试用 gzip 算法解压
  而直接报错（不是合法的 gzip 流）。
- 上面这条的前提是"标准的 HTTP 响应"。如果这个接口以后要给**非浏览器
  运行时**用（比如小程序的 `wx.request`、React Native 的 `fetch` polyfill、
  或者某些不遵循标准 Fetch 语义的 SDK），不同运行时对
  `Content-Encoding: gzip` 的自动解压支持不一定完整，届时要么确认目标
  运行时的行为、要么就得在那个客户端手动引入 gunzip 库处理。
- **后端这边**：`GZIPOutputStream` 必须显式 `close()`（代码里用了
  try-with-resources）才会把 gzip 格式要求的尾部（CRC32 校验和 + 长度）
  刷出来——如果只 `flush()` 不 `close()`，产出的字节流在有些解压实现下
  能"凑合"解出内容，但严格校验 CRC 的解压器会报错，是个隐蔽坑。
- **反向代理会不会二次压缩？** 项目里 `docker/nginx.conf` 现在
  `gzip_types` 没有包含 `application/x-protobuf`，不会对这个接口的响应
  再压一次；即使以后手滑加进去，Nginx 的标准行为是"响应已经带
  `Content-Encoding` 头就跳过自己的 gzip 模块"，不会出现双重压缩。但如果
  以后换了别的反向代理/CDN，这个"看到已有 Content-Encoding 就跳过"的
  行为不是所有实现都保证，上线前建议实际抓包确认一次。

---

## 3. 跨域 / 请求头

**这个项目在生产环境是真跨域的**——前端部署在 Cloudflare
(`frontend/wrangler.toml`)，后端在 Railway
(`frontend/.env.production` 里的 `VITE_API_BASE_URL`)，不是同源。这几点
在跨域场景下必须确认：

- **`Accept-Encoding` 不能由前端 JS 设置。** 它是 Fetch 规范里的
  "forbidden header name"，`fetch()`/`axios` 里手动 `headers` 加这一项会被
  浏览器静默忽略（这也是我们上一版代码里的一个错误，已经改掉了）。
  浏览器会自动在每个请求上带上它自己支持的编码列表，后端
  `QuoteIntradayProtoController` 读的就是这个"浏览器自动带的"
  `Accept-Encoding` 请求头，前端完全不用插手。
- **CORS 预检（preflight）**：当前 `CorsConfig.java` 里
  `setAllowedHeaders(List.of("*"))` 已经放行所有请求头，这次新接口用到的
  `Accept` 头不会触发额外配置工作。但如果以后这个二进制接口要新增自定义
  请求头（比如客户端版本号、协议版本协商），要记得同步加进
  `allowedHeaders`，否则会在预检阶段被拦。
- **`Content-Type: application/x-protobuf` 会不会触发预检？** 会。凡是
  `Content-Type` 不在 CORS 规范定义的"简单请求"白名单
  （`text/plain`/`multipart/form-data`/`application/x-www-form-urlencoded`）
  内，浏览器都会先发一次 `OPTIONS` 预检请求。不过这里是 **GET** 请求，
  `Content-Type` 是响应头（服务端返回的），不是请求头，本身不会触发预检；
  真正要注意预检的场景是"以后如果做 POST + protobuf body 的写接口"，那时
  请求的 `Content-Type: application/x-protobuf` 才会让浏览器发预检，要确认
  `CorsConfig` 里 `allowedMethods` 包含 `OPTIONS`（现在已经包含）。
- **响应头暴露（`Access-Control-Expose-Headers`）**：现在代码里前端不需要
  用 JS 读取 `Content-Encoding`/`Content-Length` 这些响应头（解压是浏览器
  自动做的，业务代码感知不到），所以不需要改 `CorsConfig.java` 里现有的
  `setExposedHeaders(List.of("ETag"))`。**但如果以后要在前端加"实际传输
  字节数对比"这种调试/埋点逻辑**（比如想在 Debug 面板里显示这次请求压缩
  前后各多大），就得把 `Content-Length` 也加进 `exposedHeaders`，否则跨域
  场景下 `resp.headers.get('Content-Length')` 拿到的永远是 `null`——这跟
  `EtagConfig.java` 注释里解释的 ETag 不 expose 就读不到是同一个原因。
- **`credentials` 场景下 `allowedOrigins` 不能用 `*`。** 现有配置已经是
  按环境变量传具体域名列表（`CORS_ALLOWED_ORIGINS`）而不是通配符，这个新
  接口沿用同一个 `CorsConfigurationSource`，不用额外处理，只是提醒：如果
  以后图省事把 `allowedOrigins` 改成 `*`，会跟当前 `setAllowCredentials(true)`
  冲突（浏览器直接拒绝这种组合），到时候比较难排查。

---

## 4. 其他容易漏掉的点

- **`null` 语义丢失**：Protobuf 的 `string`/`int64` 都没有 `null`，只有
  "默认值"。`QuoteVO.industry` 这类数据库里可能是 `null` 的字段，如果哪天
  加进这套二进制协议，"没有值"和"真的是空字符串"在 proto3 里区分不出来，
  除非显式用 `optional` 关键字（生成 `hasXxx()` 判断方法）。这次的
  `QuoteIntradayVO` 里都是行情数值字段，业务上本来就不该是 `null`，暂时
  没这个问题，但以后往这套协议里加字段时要留意。
- **前后端 `.proto` 现在是手动同步的两份文件**
  （`backend/stock-common/src/main/proto/quote_intraday.proto` 和
  `frontend/src/api/proto/quoteIntraday.proto`），改动 schema 时必须两边
  一起改，忘了同步会导致解码出来的字段错位或者直接抛异常。接口一旦
  稳定、要长期维护，建议用 CI 脚本做一次"两份文件内容一致性校验"，或者
  干脆把 `.proto` 放单独的共享目录，前后端构建时各自从那里拷贝/引用，
  避免人工同步出错。
- **精度 round-trip**：`BigDecimal` → 定点 `long`（乘 100 四舍五入）→
  前端还原成 `number`（除以 100）这个链路，理论上对"两位小数"的价格数据
  是无损的；但如果后端某天引入了三位小数的数据（比如某些指数、期权），
  `SCALE = 100` 就不够用了，需要同步改大 `SCALE` 并重新约定，前后端任何
  一边 SCALE 不一致都会导致数值直接错一个数量级，且不会报错——这种"静默
  错误"是定点数方案最大的风险点，建议加一个字段级的单元测试固定几个
  边界值（比如 `0.00`、`9999.99`、负数）来防止回归。

---

## 后续步骤建议

1. 先在本地 `mvn -f backend/stock-parent/pom.xml compile` 跑一次，确认
   `protobuf-maven-plugin` 能正常拉取 `protoc` 并生成
   `com.stockapp.common.proto.*` 类（本沙箱环境没有外网，没能实际跑这一步，
   交付的是源码 + 配置，请在你本地/CI 环境验证编译）。
2. `cd frontend && npm install` 装上新增的 `protobufjs` 依赖后
   `npm run dev`，用浏览器 DevTools Network 面板对比同一只股票
   `/quote` + `/intraday`（JSON+gzip，两次请求）跟 `/quote-intraday.pb`
   （Protobuf+gzip，一次请求）的实际传输字节数，量化收益后再决定要不要
   往其他接口铺开。
3. 确认收益后，把这个 mapper + controller 的模式复制到其他高频接口
   （K 线、批量行情等），并考虑用 pbjs/pbts 走 codegen 替换掉现在前端的
   动态 `protobuf.parse()`，换取编译期类型安全。

---

## 两套协议现在怎么切换（2026-09 新增）

`StockDetailView.vue` 的 `onActivated` 组合刷新（KeepAlive 切回详情页时
同时刷新 quote + 分时）现在走的是统一入口
`frontend/src/api/quoteIntradayGateway.ts` 里的 `fetchQuoteIntraday()`，
内部按 `frontend/src/config/quoteProtocol.ts` 的 `QUOTE_PROTOCOL` 常量分流：

```ts
// frontend/src/config/quoteProtocol.ts
export const QUOTE_PROTOCOL: QuoteProtocol = 'json'      // 改成 'protobuf' 即可切换
```

改这一个值、不需要动调用方代码：
- `'json'`：走原来的 `getDetailBootstrap()`（单次组合请求，已经是优化过的
  JSON+Gzip 路径，不是逐个请求 quote/intraday）。
- `'protobuf'`：走 `getQuoteIntradayProto()`（`/quote-intraday.pb`）。

**这个开关目前只覆盖一个调用点**，不是"全局协议开关"，原因见
`quoteIntradayGateway.ts` 顶部注释，这里摘要一下（详细论证看那个文件）：

| 调用点 | 现状 | 为什么没接进这个开关 |
|---|---|---|
| `StockDetailView.vue` `onActivated` | ✅ 已接入 | quote+intraday 本来就无条件一起刷新，语义完全对等 |
| `loadQuote()`（10 秒轮询） | 仍是纯 JSON `getQuote` | 只要 quote，Protobuf 接口会强行搭上整个分时图，纯粹增加流量 |
| `switchTab('intraday')` | 仍是纯 JSON `getIntraday` | 同理，只要 intraday 不要 quote |
| `quoteIntradaySync.ts` 全量后台同步 | 仍是纯 JSON | quote/intraday 各自独立判断本地缓存新鲜度、按需分别请求，换成组合接口会强迫"只要有一个过期就把两个都重新拉"，是真实的请求量倒退，不是协议层面的等价替换 |
| 冷启动（`onMounted` → `detailPrefetch.ts`） | 仍是纯 JSON | 牵扯预取缓存、viewport 命中判断，还没设计对应的 Protobuf 版本 |

如果以后要往其他调用点铺开，思路是**先确认该调用点本来就是"quote 和
intraday 无条件绑定获取"**，符合条件的才适合直接换成
`fetchQuoteIntraday()`；不符合条件的（像上表后三行）要么保持现状，要么
单独给 Protobuf 那边设计对应的"只要一个字段"的瘦身版接口，不能图省事
一刀切全部指向同一个组合接口。
