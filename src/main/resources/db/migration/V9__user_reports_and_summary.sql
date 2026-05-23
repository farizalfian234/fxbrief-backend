-- Phase 3B — per-user report persistence.
--
-- (1) Add a short summary column on the shared analysis row. The summary is
--     produced at the same time as the rest of the market_analysis payload
--     and is copied verbatim into every user_reports row that references
--     this analysis. Storing it on the parent row avoids regenerating per
--     user and keeps reused cache-hit reports cheap (no extra computation).
ALTER TABLE market_analysis
    ADD COLUMN summary VARCHAR(100) NOT NULL DEFAULT '';

-- Strip the default after backfill so future inserts must populate explicitly.
ALTER TABLE market_analysis
    ALTER COLUMN summary DROP DEFAULT;

-- (2) Per-user report row. One row per user per forex market day at most;
--     the unique constraint provides the structural backstop alongside the
--     transactional advisory lock taken at generation time.
--
--     `market_analysis_id` is a foreign key into the shared analysis — no
--     content duplication. `summary` is denormalised from the parent for
--     fast list-render in Phase 3C (history page) without an extra join.
--     `counted_against_limit` records whether this report consumed a
--     report credit (false on the "markets consolidating" zero-content
--     case, per PRD §5.5).
CREATE TABLE user_reports (
    id                   BIGSERIAL    PRIMARY KEY,
    user_id              BIGINT       NOT NULL,
    market_analysis_id   BIGINT       NOT NULL,
    summary              VARCHAR(100) NOT NULL,
    forex_market_date    DATE         NOT NULL,
    counted_against_limit BOOLEAN     NOT NULL,
    plan_at_generation   SMALLINT     NOT NULL,
    generated_at         TIMESTAMPTZ  NOT NULL,
    is_archived          BOOLEAN      NOT NULL DEFAULT FALSE,
    archived_at          TIMESTAMPTZ,
    CONSTRAINT fk_user_reports_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_reports_market_analysis
        FOREIGN KEY (market_analysis_id) REFERENCES market_analysis (id),
    CONSTRAINT fk_user_reports_plan_at_generation
        FOREIGN KEY (plan_at_generation) REFERENCES subscription_plans (id),
    CONSTRAINT uq_user_reports_user_forex_date
        UNIQUE (user_id, forex_market_date)
);

CREATE INDEX idx_user_reports_user_id_generated_at
    ON user_reports (user_id, generated_at DESC);

CREATE INDEX idx_user_reports_is_archived_forex_date
    ON user_reports (is_archived, forex_market_date);
