CREATE TABLE subscription_plans (
    id           SMALLINT       PRIMARY KEY,
    name         VARCHAR(32)    NOT NULL UNIQUE,
    price        NUMERIC(10, 2) NOT NULL,
    report_count INT            NOT NULL,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_subscription_plans_price        CHECK (price >= 0),
    CONSTRAINT ck_subscription_plans_report_count CHECK (report_count >= 0)
);

INSERT INTO subscription_plans (id, name, price, report_count) VALUES
    (1, 'FREE',     0.00,  3),
    (2, 'BASIC',   10.00, 20),
    (3, 'PREMIUM', 20.00, 20);

CREATE TABLE subscriptions (
    id                BIGSERIAL   PRIMARY KEY,
    user_id           BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan_id           SMALLINT    NOT NULL REFERENCES subscription_plans(id),
    remaining_reports INT         NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_subscriptions_user                  UNIQUE (user_id),
    CONSTRAINT ck_subscriptions_remaining_nonnegative CHECK (remaining_reports >= 0)
);

CREATE INDEX idx_subscriptions_plan_id ON subscriptions (plan_id);
