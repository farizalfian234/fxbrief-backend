package com.fxbrief.report.entity;

/**
 * The four preference categories supported by Addition 3.
 *
 * <p>Each category selects a different scoring strategy in
 * {@code PreferenceScorer}. Exactly one preference (one type, one value) is
 * active per user at a time — the {@code user_preferences} table enforces
 * this via the UNIQUE constraint on {@code user_id}.
 *
 * <p>Adding a new category requires (a) a new enum constant here, (b) a
 * new branch in {@code PreferenceScorer}, (c) the matching values in
 * {@link PreferenceValue}.
 */
public enum PreferenceType {
    TRADING_STYLE,
    PREFERRED_SESSION,
    RISK_PROFILE,
    FAVORITE_PAIR
}
