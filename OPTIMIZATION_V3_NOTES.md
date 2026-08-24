# v3 性能与一致性优化说明

## 1. 路由级预加载（秒开）

- `router/index.ts` 导出 `viewLoaders` loader 表，路由与预加载共用同一个动态 import（chunk 只有一份）
- `utils/preload.ts`：`requestIdleCallback`（Safari 降级 setTimeout）串行预载 行情/自选/交易/我的 → 详情页 → 搜索页；
  检测到 `saveData` 或 2G 时整体跳过
- `TabBar.vue` / `StockRow.vue`：`touchstart` / `mouseenter` 提前触发对应 chunk 下载

**取舍**
| 指标 | 影响 |
|---|---|
| 首屏时间 | 基本不变。预加载在 idle 回调中执行，不与首屏关键资源抢带宽，入口 chunk 体积未变 |
| 总下载流量 | 增加。原本"没点过的页面永不下载"，现在每个用户会下载全部 Tab 页 + 详情页 + 图表库 |
| 建议 | 本 App 用户基本会逛遍 5 个 Tab，这笔交换划算；省流量模式用户已自动跳过 |

## 2. 数据一致性（本轮最关键）

**发现：你以为已修复的 K 线 bug 实际并未修复。** `getKline` 的随机游走步数仍等于
`days` 参数，而 `MockDataInitializer` 用 250 天入库、`getIntraday` 内部用 2 天算昨收，
同一个交易日两条路径算出的收盘价完全不同。

修复内容：
- `getKline` 改为从固定锚点 `ANCHOR = 2024-01-02` 走满全程到昨天，再截取尾部 N 根。
  同一 code + 同一日历日现在是纯函数，任意 `days` 结果恒等
- 新增 `MEAN_REVERSION = 0.008` 均值回归。走满全程后步数达 690+ 且逐日增长，
  纯随机游走会让茅台漂到 400 元；加回归后长期稳定在基准价 ±20% 内
- `getIntraday` 的昨收改为调 `closeOf()` 从同一份历史序列取值，不再独立生成
- 新增 `HISTORY_CACHE`（进程内、按天）避免每次请求重算 690 根
- `MockDataInitializer` 由"有数据就跳过"改为"**补齐缺失的日 K**"，
  解决 DB 数据永远停在部署当天、与实时昨收越差越远的问题；周/月 K 覆盖式重算
- 新增 2 个回归测试：`kline_sameDayMustMatchAcrossDifferentDayCounts`、
  `intradayPreClose_shouldMatchKlineHistory`

**关于 getQuote 加缓存**：不需要新增，`MarketService.getQuote` 已走 Redis
`QUOTE_TTL = 10 秒`。真正的缝隙在于 `/api/sim/account` 与 `/api/sim/positions`
是两个独立请求，可能跨越缓存过期边界 → 已新增合并接口 `GET /api/sim/portfolio`，
两者共用同一次持仓计算，保证 `总资产 = 可用 + 冻结 + Σ持仓市值` 严格成立。

## 3. 打包体积

根因确认：两个图表组件用 `import * as echarts from 'echarts'` 引入全量包，
被静态引入 StockDetailView → 整个 ECharts 打进该页 chunk。

- 新增 `components/charts/echarts.ts`：改用 `echarts/core` 按需注册
  （Line / Bar / Candlestick + Grid / Tooltip / AxisPointer / DataZoom / Legend / MarkLine + CanvasRenderer）
- `vite.config.ts` 加 `manualChunks`：echarts + zrender 独立 chunk，vue 全家桶 + axios 合成 vendor
- 顺带把 `echarts.graphic.LinearGradient` 换成声明式渐变对象，减少对全量包的依赖

预期：gzip 347kB → 约 150-180kB；且图表库独立缓存，业务代码更新时用户无需重新下载。

**未做**：把图表组件改成 `defineAsyncComponent`。拆 chunk 后 echarts 本来就只随详情页加载，
再加一层异步反而增加复杂度和一次额外往返，收益为负。

## 4. 其他改进

| 项 | 内容 |
|---|---|
| 后台切回立即刷新 | `stores/market.ts` 监听 `visibilitychange`，锁屏回来不用干等 10 秒 tick |
| 轮询守卫 | `OrderView.vue` 的 5 秒价格轮询补上 `visibilityState` 判断 |
| 全局错误兜底 | `main.ts` 加 `app.config.errorHandler` + `unhandledrejection`，未捕获异常转 toast，不再静默白屏 |
| 网络错误提示 | `api/http.ts` 区分超时/断网/5xx，5 秒节流后弹一次 toast（多个轮询同时失败不刷屏） |
| 格式化去重 | `fmtMoney` / `fmtSigned` / `fmtSignedPercent` 收进 `utils/format.ts`，4 个视图的重复实现已删除 |
| 消除 N+1 查询 | `StockService.mapByIds()` 批量查股票，`listPositions` / `listTrades` 不再逐条查库 |

## ⚠️ 部署注意

**首次部署本版本后端后，必须清空 `stock_kline` 表**：

```sql
TRUNCATE stock_kline;
```

因为随机游走的锚点和算法都变了，旧数据是用老算法生成的，不清掉的话
"补齐"逻辑只会往后接新数据，历史段仍是旧值，昨收和 K 线还是对不上。
清空后重启服务，`MockDataInitializer` 会用新算法重新生成全量 250 根。

其余照旧：推 GitHub → Render 重建后端 → Cloudflare 重建前端。

## 校验情况

- 81 个 Java 文件：括号配平 / 包路径 / 类名一致性全部通过
- 36 个 TS / Vue 文件：语法与基础类型检查全部通过
- 修复逻辑已用等价 Python 实现验证：新算法任意 days 结果恒等；旧算法 days=2 与 days=250
  同日收盘价相差 1114 vs 1297，bug 复现确认
- 容器无网络，未做真实 `mvn package` / `vite build`；如构建报错请把日志发我
