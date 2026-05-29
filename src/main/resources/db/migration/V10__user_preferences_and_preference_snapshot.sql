-- Addition 3 — preference-based compatibility scoring.
--
-- (1) user_preferences — one row per user, present only if the user has set
--     a preference. Absence of a row means "no preference; skip scoring".
--     One preference per user at a time (the UNIQUE on user_id enforces this):
--     setting a new preference replaces the previous row in place.
--
--     preference_type identifies which of the four enums applies; this is
--     also what selects the scoring strategy in PreferenceScorer. preference_value
--     stores the chosen enum value as a string. Both columns are validated
--     in Java against the PreferenceType / PreferenceValue enums before insert;
--     no CHECK constraint is added so the enum set can evolve without a
--     migration.
CREATE TABLE user_preferences (
    id               BIGSERIAL    PRIMARY KEY,
    user_id          BIGINT       NOT NULL,
    preference_type  VARCHAR(32)  NOT NULL,
    preference_value VARCHAR(32)  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_user_preferences_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_user_preferences_user_id
        UNIQUE (user_id)
);

CREATE INDEX idx_user_preferences_user_id
    ON user_preferences (user_id);

-- (2) user_reports — two new nullable columns capturing the per-user
--     preference state at generation time. Both are nullable; both are
--     null when generation happened with no preference active. When
--     populated:
--
--     preference_snapshot  → {"preferenceType":"TRADING_STYLE",
--                             "preferenceValue":"SWING_TRADER"}
--     final_display_scores → {"EUR/USD": 7.4, "GBP/USD": 5.8, ...}
--
--     The snapshot is the source of truth for reordering this report on
--     every subsequent read. Changing user_preferences later never
--     affects existing rows.
ALTER TABLE user_reports
    ADD COLUMN preference_snapshot  JSONB NULL,
    ADD COLUMN final_display_scores JSONB NULL;
