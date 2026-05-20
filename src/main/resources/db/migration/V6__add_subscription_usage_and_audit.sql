CREATE TABLE subscription_usage (
    id                 BIGSERIAL   PRIMARY KEY,
    user_id            BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    forex_market_date  DATE        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_subscription_usage_user_day UNIQUE (user_id, forex_market_date)
);

CREATE INDEX idx_subscription_usage_user_day
    ON subscription_usage (user_id, forex_market_date);

CREATE TABLE subscription_audit_logs (
    id             BIGSERIAL   PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    action         VARCHAR(64) NOT NULL,
    old_plan       SMALLINT    REFERENCES subscription_plans(id),
    new_plan       SMALLINT    REFERENCES subscription_plans(id),
    old_remaining  INT,
    new_remaining  INT,
    performed_by   BIGINT      REFERENCES users(id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_subscription_audit_logs_old_remaining
        CHECK (old_remaining IS NULL OR old_remaining >= 0),
    CONSTRAINT ck_subscription_audit_logs_new_remaining
        CHECK (new_remaining IS NULL OR new_remaining >= 0)
);

CREATE INDEX idx_subscription_audit_logs_user_id
    ON subscription_audit_logs (user_id);
CREATE INDEX idx_subscription_audit_logs_created_at
    ON subscription_audit_logs (created_at);
