-- Phase 3A v3 — shared analysis per fetch cycle.
--
-- (1) Tag every market_data_cache row with the prefetch-cycle that produced it.
--     `current fetch_id` for analysis purposes is the most recent fetch_id where
--     all 32 (pair, timeframe) rows agree — see MarketDataReader.resolveCurrentFetchId.
ALTER TABLE market_data_cache
    ADD COLUMN fetch_id UUID;

CREATE INDEX idx_market_data_cache_fetch_id
    ON market_data_cache (fetch_id);

-- (2) Shared market analysis row keyed by fetch_id.
--     Lazily populated on the first user request per cycle. All subsequent
--     users within the same cycle reuse this row — Claude API is not called.
--
--     `payload` is the full ReportPayload as JSONB (all 8 PairAnalyses,
--     bestPair, marketsConsolidating, timestamps). `narrative_mode`
--     identifies how the narratives were produced (MEGA, PER_PAIR_FALLBACK,
--     or HYBRID — see DECISIONS D-045).
CREATE TABLE market_analysis (
    id                    BIGSERIAL    PRIMARY KEY,
    fetch_id              UUID         NOT NULL,
    payload               JSONB        NOT NULL,
    narrative_mode        VARCHAR(32)  NOT NULL,
    invalid_pair_count    SMALLINT     NOT NULL DEFAULT 0,
    market_data_fetched_at TIMESTAMPTZ NOT NULL,
    calendar_fetched_at   TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_market_analysis_fetch_id UNIQUE (fetch_id)
);

CREATE INDEX idx_market_analysis_created_at
    ON market_analysis (created_at);

-- (3) Shadow QA log — written when the engine runs the per-pair shadow path
--     alongside the mega-call for divergence measurement (DECISIONS D-046).
--     Toggle via fxbrief.analysis.shadow-narrative-logging.enabled.
--     This table is for operational measurement only; nothing reads it in
--     business logic. Safe to truncate at any time.
CREATE TABLE narrative_qa_log (
    id                BIGSERIAL    PRIMARY KEY,
    fetch_id          UUID         NOT NULL,
    pair              VARCHAR(16)  NOT NULL,
    mega_narrative    TEXT,
    per_pair_narrative TEXT,
    diverged          BOOLEAN      NOT NULL,
    divergence_reason VARCHAR(255),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_narrative_qa_log_fetch_id
    ON narrative_qa_log (fetch_id);
CREATE INDEX idx_narrative_qa_log_created_at
    ON narrative_qa_log (created_at);
