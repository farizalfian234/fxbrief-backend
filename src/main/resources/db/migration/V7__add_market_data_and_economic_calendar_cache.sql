CREATE TABLE market_data_cache (
    id          BIGSERIAL    PRIMARY KEY,
    pair        VARCHAR(16)  NOT NULL,
    timeframe   VARCHAR(8)   NOT NULL,
    payload     JSONB        NOT NULL,
    fetched_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_market_data_cache_pair_timeframe UNIQUE (pair, timeframe)
);

CREATE INDEX idx_market_data_cache_fetched_at
    ON market_data_cache (fetched_at);

CREATE TABLE economic_calendar_cache (
    id          BIGSERIAL    PRIMARY KEY,
    scope       VARCHAR(32)  NOT NULL,
    payload     JSONB        NOT NULL,
    fetched_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_economic_calendar_cache_scope UNIQUE (scope)
);

CREATE INDEX idx_economic_calendar_cache_fetched_at
    ON economic_calendar_cache (fetched_at);
