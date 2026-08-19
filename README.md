# 简牛行情 · 极简版同花顺

一个完整可运行的全栈股票行情 APP：**Vue 3 移动端 + Spring Boot 多模块后端 + PostgreSQL + Redis + Docker**。
默认使用内置 **Mock 行情数据源**，无需任何第三方 API Key 即可启动并看到完整效果。

> ⚠️ 当前所有行情、指数与新闻均为系统生成的模拟数据（UI 中带"模拟行情"标识），不构成投资建议。

---

## 功能

- 股票搜索（代码 / 名称模糊搜索）
- 股票列表、市场指数（上证 / 深证成指 / 创业板指）、热门股票
- 股票详情：实时行情（价格 / 涨跌 / 高低 / 量额）
- 分时图（价格线 + 均价线 + 成交量 + 十字光标 + 数据提示）
- 日 K / 周 K / 月 K（缩放、横向拖动、十字光标、OHLC 提示）
- 技术指标：MA5/10/20/60、MACD(DIF/DEA/MACD)、KDJ(K/D/J)、RSI(6/12/24)
  —— **全部由后端统一计算**，前端只绘制，保证一致性
- 自选股（防重复、删除、排序、仅本人可操作）
- 个股新闻（列表 + 详情，Mock 内容）
- 用户注册 / 登录（BCrypt + JWT）、统一 Result 返回、全局异常处理
- Redis 缓存（行情 / 指数 / 热门，全部带 TTL）、行情同步定时任务
- Docker Compose 一键启动

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 21 · Spring Boot 3.3 · Spring Web/Validation/Security · MyBatis-Plus · JWT (jjwt 0.12) · Lombok |
| 数据 | PostgreSQL 16 · Redis 7 |
| 前端 | Vue 3 · TypeScript · Vite · Pinia · Vue Router · Axios · ECharts 5 |
| 部署 | Docker · docker-compose · Nginx |

## 项目结构

```
stock-app/
├── backend/
│   ├── stock-parent/     # 父 POM（版本与依赖管理）
│   ├── stock-common/     # Result / 异常 / JwtUtil / 指标计算 / VO·DTO
│   ├── stock-dao/        # Entity + MyBatis-Plus Mapper
│   ├── stock-service/    # 业务：行情/K线/指标/自选/新闻/用户 + MarketDataProvider(Mock)
│   ├── stock-job/        # 定时任务：行情刷新 Redis、行情快照落库
│   └── stock-api/        # Controller / Security / 全局异常 / 启动入口
├── frontend/             # Vue 3 移动端（首页/行情/搜索/详情/自选/新闻/登录/注册/我的）
├── database/             # schema.sql + data.sql（幂等）
├── docker/               # backend / frontend Dockerfile + nginx.conf
├── docker-compose.yml
├── .env.example
└── README.md
```

## 环境要求

- Docker 方式：Docker 20+ / Docker Compose v2（推荐，零配置）
- 本地开发：JDK 21、Maven 3.9+、Node.js 18+、PostgreSQL 16、Redis 7

## 快速启动（Docker，推荐）

```bash
cp .env.example .env        # 可选：修改 JWT_SECRET 等
docker compose up -d --build
```

启动后：

- 前端：http://localhost:8081
- 后端 API：http://localhost:8080/api/market/index
- PostgreSQL：localhost:5432（stockapp/stockapp123）
- Redis：localhost:6379

首次启动后端会自动：建表（幂等 SQL）→ 插入 8 只股票 → 生成 250 根日 K 及周/月 K、Mock 新闻。

## 本地开发启动

### 1. 数据库 & Redis

```bash
docker compose up -d postgres redis
# 或使用本地已装好的 PostgreSQL/Redis，按 .env.example 配置环境变量
```

（后端启动时会自动执行 `classpath:db/schema.sql + data.sql`，无需手工建表）

### 2. 后端

```bash
cd backend
mvn -f stock-parent/pom.xml clean package        # 编译 + 测试
java -jar stock-api/target/stock-api-1.0.0.jar   # 默认连接 localhost 的 PG/Redis
```

### 3. 前端

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173 （已配置 /api 代理到 8080）
```

## 环境变量（.env.example）

| 变量 | 说明 | 默认 |
|---|---|---|
| DB_HOST / DB_PORT / DB_NAME / DB_USERNAME / DB_PASSWORD | PostgreSQL | localhost / 5432 / stockapp / stockapp / stockapp123 |
| REDIS_HOST / REDIS_PORT / REDIS_PASSWORD | Redis | localhost / 6379 / 空 |
| JWT_SECRET | JWT 签名密钥（≥32 字节，**生产必须更换**） | 内置开发密钥 |
| JWT_EXPIRE_HOURS | Token 有效期 | 24 |
| MARKET_DATA_PROVIDER | 行情数据源 mock / real | mock |
| MARKET_API_KEY / NEWS_API_KEY | 真实行情/新闻源密钥（Mock 模式不需要） | 空 |
| CORS_ALLOWED_ORIGINS | 允许的前端域名（逗号分隔） | localhost:5173, localhost:8081 |

所有敏感信息只通过环境变量注入，代码中不含任何真实密钥。

## API 文档

统一返回：`{"code":0,"message":"success","data":{}}`；错误如 `{"code":40001,"message":"股票不存在","data":null}`。

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | /api/stocks/search?keyword= | 搜索 | 否 |
| GET | /api/stocks | 股票列表 | 否 |
| GET | /api/stocks/{code} | 详情 | 否 |
| GET | /api/stocks/{code}/quote | 实时行情 | 否 |
| GET | /api/stocks/{code}/intraday | 分时 | 否 |
| GET | /api/stocks/{code}/kline?period=day\|week\|month&limit= | K 线 | 否 |
| GET | /api/stocks/{code}/indicators?period=&limit= | MA/MACD/KDJ/RSI | 否 |
| GET | /api/stocks/{code}/news | 个股新闻 | 否 |
| GET | /api/news/{id} | 新闻详情 | 否 |
| GET | /api/market/index | 市场指数 | 否 |
| GET | /api/market/hot | 热门股票 | 否 |
| POST | /api/auth/register | 注册 | 否 |
| POST | /api/auth/login | 登录（返回 JWT） | 否 |
| GET | /api/user/profile | 当前用户 | ✅ Bearer |
| GET | /api/watchlist | 自选列表 | ✅ |
| POST | /api/watchlist/{stockId} | 添加自选 | ✅ |
| DELETE | /api/watchlist/{stockId} | 删除自选 | ✅ |
| GET | /api/watchlist/contains/{stockId} | 是否已自选 | ✅ |

主要错误码：40000 参数错误 · 40001 股票不存在 · 40003 用户名已存在 · 40004 用户名或密码错误 · 40005 重复自选 · 40100 未登录 · 40101 Token 过期 · 50001 数据库异常 · 50002 缓存异常。

## Redis Key 设计（均带 TTL）

| Key | 内容 | TTL |
|---|---|---|
| stock:quote:{code} | 实时行情 | 10s |
| stock:intraday:{code} | 分时 | 30s |
| stock:info:{code} | 股票信息 | 6h |
| stock:hot | 热门股票 | 5min |
| market:index | 市场指数 | 10s |

Redis 故障时自动降级为直查数据源，行情功能不受影响。

## 测试

```bash
cd backend && mvn -f stock-parent/pom.xml test
```

- `IndicatorCalculatorTest`：MA / MACD / KDJ / RSI 数学正确性
- `MockMarketDataProviderTest`：K 线确定性、OHLC 合法性、行情一致性、Mock 标识
- `UserServiceTest`：注册 BCrypt 加密、重名拒绝、登录 JWT、错误密码
- `WatchlistServiceTest`：重复自选拒绝、删除不存在报错
- `AuthControllerTest` / `StockControllerTest`（MockMvc）：正常请求 / 参数错误 / 不存在股票 / 错误密码
- `JwtAuthTest`：JWT 生成、解析、过期、篡改、弱密钥拒绝

数据库层通过唯一约束保障：K 线 `(stock_id, period_type, trade_date)`、自选 `(user_id, stock_id)`、用户名唯一、行情快照 `(stock_id, trade_time)`。

## Mock 行情 与 真实行情接入

架构：

```
MarketDataProvider（接口）
 ├── MockMarketDataProvider   ← 默认，@ConditionalOnProperty(market.data.provider=mock)
 └── RealMarketDataProvider   ← 未来接入
```

接入真实行情的步骤：

1. 在 `stock-service` 新建 `RealMarketDataProvider implements MarketDataProvider`，
   加 `@ConditionalOnProperty(name="market.data.provider", havingValue="real")`；
2. 用 `MARKET_API_KEY` / `NEWS_API_KEY` 环境变量注入密钥；
3. 设置 `MARKET_DATA_PROVIDER=real` 重启即可，业务代码零改动；
4. 关闭前端"模拟行情"标识（行情返回的 `mock` 字段会自动变为 false）。

## 常见问题

- **端口冲突**：修改 docker-compose.yml 中 8080/8081/5432/6379 的宿主机映射。
- **后端连不上数据库**：确认 PG 已就绪（compose 已配置健康检查依赖）；本地裸跑请核对 DB_* 环境变量。
- **前端 401**：Token 过期会自动跳转登录页，重新登录即可。
- **想重置数据**：`docker compose down -v` 删除数据卷后重启。
- **默认账号**：无内置账号，打开 APP → 我的 → 注册即可（如 demo / demo123456）。

## 部署

生产环境建议：更换 `JWT_SECRET` 与数据库密码 → 配置 `CORS_ALLOWED_ORIGINS` 为真实域名 → `docker compose up -d --build` → 在前置网关（如 Nginx/云 LB）上配置 HTTPS。
