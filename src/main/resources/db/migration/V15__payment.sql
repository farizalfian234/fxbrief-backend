-- Midtrans payments and USD/IDR exchange rate.

ALTER TABLE users
    ADD COLUMN payment_beta_access BOOLEAN NOT NULL DEFAULT false;

-- Single-row table: scope is a fixed sentinel ('CURRENT') with a UNIQUE constraint so the
-- daily/startup fetch can upsert in place via INSERT ... ON CONFLICT, mirroring the
-- economic_calendar_cache pattern. There is always exactly one row.
CREATE TABLE exchange_rates (
    id          BIGSERIAL PRIMARY KEY,
    scope       VARCHAR(16) NOT NULL UNIQUE,
    usd_to_idr  DECIMAL(18, 6) NOT NULL,
    fetched_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE payment_transactions (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    midtrans_order_id   VARCHAR(128) NOT NULL UNIQUE,
    plan                VARCHAR(16) NOT NULL,
    amount_usd          DECIMAL(10, 2) NOT NULL,
    amount_idr          BIGINT NOT NULL,
    exchange_rate_used  DECIMAL(18, 6) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    paid_at             TIMESTAMPTZ NULL,
    created_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_payment_transactions_user_id ON payment_transactions (user_id);

CREATE TABLE payment_callbacks (
    id                  BIGSERIAL PRIMARY KEY,
    midtrans_order_id   VARCHAR(128) NOT NULL,
    raw_payload         JSONB NOT NULL,
    processed_at        TIMESTAMPTZ NULL,
    created_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_payment_callbacks_order_id ON payment_callbacks (midtrans_order_id);
