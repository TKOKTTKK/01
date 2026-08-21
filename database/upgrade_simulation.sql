-- =========================================================
-- 第二阶段升级：模拟交易表（幂等，可直接在 TablePlus 分块执行）
-- =========================================================

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
