-- =========================================================
-- 极简版同花顺 - PostgreSQL Schema
-- 所有建表语句幂等（IF NOT EXISTS），可重复执行
-- =========================================================

-- 股票基础信息
CREATE TABLE IF NOT EXISTS stock (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(16)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    market      VARCHAR(8)   NOT NULL,           -- SH / SZ
    industry    VARCHAR(64),
    status      SMALLINT     NOT NULL DEFAULT 1, -- 1 正常 0 停牌/退市
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stock_code UNIQUE (code)
);
CREATE INDEX IF NOT EXISTS idx_stock_name ON stock (name);

-- 实时行情快照
CREATE TABLE IF NOT EXISTS stock_quote (
    id             BIGSERIAL PRIMARY KEY,
    stock_id       BIGINT        NOT NULL REFERENCES stock (id),
    price          NUMERIC(12,3) NOT NULL,
    open_price     NUMERIC(12,3) NOT NULL,
    high_price     NUMERIC(12,3) NOT NULL,
    low_price      NUMERIC(12,3) NOT NULL,
    pre_close      NUMERIC(12,3) NOT NULL,
    change_amount  NUMERIC(12,3) NOT NULL,
    change_percent NUMERIC(8,3)  NOT NULL,
    volume         BIGINT        NOT NULL DEFAULT 0,
    amount         NUMERIC(20,2) NOT NULL DEFAULT 0,
    trade_time     TIMESTAMP     NOT NULL,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_quote_stock_time UNIQUE (stock_id, trade_time)
);
CREATE INDEX IF NOT EXISTS idx_quote_stock ON stock_quote (stock_id, trade_time DESC);

-- K线（日/周/月）
CREATE TABLE IF NOT EXISTS stock_kline (
    id          BIGSERIAL PRIMARY KEY,
    stock_id    BIGINT        NOT NULL REFERENCES stock (id),
    period_type VARCHAR(8)    NOT NULL,          -- day / week / month
    trade_date  DATE          NOT NULL,
    trade_time  TIMESTAMP,
    open_price  NUMERIC(12,3) NOT NULL,
    high_price  NUMERIC(12,3) NOT NULL,
    low_price   NUMERIC(12,3) NOT NULL,
    close_price NUMERIC(12,3) NOT NULL,
    volume      BIGINT        NOT NULL DEFAULT 0,
    amount      NUMERIC(20,2) NOT NULL DEFAULT 0,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_kline UNIQUE (stock_id, period_type, trade_date)
);
CREATE INDEX IF NOT EXISTS idx_kline_query ON stock_kline (stock_id, period_type, trade_date DESC);

-- 用户（user 为 PG 保留字，统一加引号）
CREATE TABLE IF NOT EXISTS "user" (
    id         BIGSERIAL PRIMARY KEY,
    username   VARCHAR(32)  NOT NULL,
    password   VARCHAR(100) NOT NULL,            -- BCrypt
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_username UNIQUE (username)
);

-- 自选股
CREATE TABLE IF NOT EXISTS user_watchlist (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT    NOT NULL REFERENCES "user" (id),
    stock_id   BIGINT    NOT NULL REFERENCES stock (id),
    sort_order INT       NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_watchlist UNIQUE (user_id, stock_id)
);
CREATE INDEX IF NOT EXISTS idx_watchlist_user ON user_watchlist (user_id, sort_order);

-- 股票新闻
CREATE TABLE IF NOT EXISTS stock_news (
    id           BIGSERIAL PRIMARY KEY,
    stock_id     BIGINT       REFERENCES stock (id),
    title        VARCHAR(255) NOT NULL,
    source       VARCHAR(64),
    url          VARCHAR(512),
    content      TEXT,
    publish_time TIMESTAMP    NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_news_stock ON stock_news (stock_id, publish_time DESC);
-- 模拟交易账户
CREATE TABLE IF NOT EXISTS simulation_account (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT        NOT NULL REFERENCES "user" (id),
    initial_cash   NUMERIC(16,2) NOT NULL,
    available_cash NUMERIC(16,2) NOT NULL,
    frozen_cash    NUMERIC(16,2) NOT NULL DEFAULT 0,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sim_account_user UNIQUE (user_id)
);

-- 模拟持仓
CREATE TABLE IF NOT EXISTS simulation_position (
    id                 BIGSERIAL PRIMARY KEY,
    account_id         BIGINT        NOT NULL REFERENCES simulation_account (id),
    stock_id           BIGINT        NOT NULL REFERENCES stock (id),
    quantity           BIGINT        NOT NULL DEFAULT 0,
    available_quantity BIGINT        NOT NULL DEFAULT 0,
    avg_cost           NUMERIC(12,3) NOT NULL DEFAULT 0,
    created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sim_position UNIQUE (account_id, stock_id)
);

-- 模拟订单
CREATE TABLE IF NOT EXISTS simulation_order (
    id         BIGSERIAL PRIMARY KEY,
    account_id BIGINT        NOT NULL REFERENCES simulation_account (id),
    stock_id   BIGINT        NOT NULL REFERENCES stock (id),
    side       VARCHAR(8)    NOT NULL,           -- BUY / SELL
    quantity   BIGINT        NOT NULL,
    price      NUMERIC(12,3) NOT NULL,
    amount     NUMERIC(16,2) NOT NULL,
    status     VARCHAR(16)   NOT NULL DEFAULT 'FILLED',
    created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sim_order_account ON simulation_order (account_id, created_at DESC);

-- 模拟成交
CREATE TABLE IF NOT EXISTS simulation_trade (
    id         BIGSERIAL PRIMARY KEY,
    order_id   BIGINT        NOT NULL REFERENCES simulation_order (id),
    account_id BIGINT        NOT NULL REFERENCES simulation_account (id),
    stock_id   BIGINT        NOT NULL REFERENCES stock (id),
    side       VARCHAR(8)    NOT NULL,
    quantity   BIGINT        NOT NULL,
    price      NUMERIC(12,3) NOT NULL,
    amount     NUMERIC(16,2) NOT NULL,
    created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sim_trade_account ON simulation_trade (account_id, created_at DESC);

-- 资金流水
CREATE TABLE IF NOT EXISTS simulation_cash_flow (
    id          BIGSERIAL PRIMARY KEY,
    account_id  BIGINT        NOT NULL REFERENCES simulation_account (id),
    type        VARCHAR(16)   NOT NULL,          -- BUY / SELL / INIT
    amount      NUMERIC(16,2) NOT NULL,          -- 正为入账，负为出账
    balance     NUMERIC(16,2) NOT NULL,          -- 变动后可用资金
    description VARCHAR(128),
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sim_cashflow_account ON simulation_cash_flow (account_id, created_at DESC);
