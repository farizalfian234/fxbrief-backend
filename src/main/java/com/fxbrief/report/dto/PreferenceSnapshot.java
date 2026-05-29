package com.fxbrief.report.dto;

import com.fxbrief.report.entity.PreferenceType;

/**
 * Captures the preference active at the moment a report was generated.
 * Persisted as JSONB on {@code user_reports.preference_snapshot} and
 * returned on the wire inside {@link ReportView} and {@link HistoryItemView}.
 *
 * <p>The snapshot is immutable once written — see DECISIONS D-056. Changing
 * {@code user_preferences} later never rewrites an existing snapshot.
 *
 * <p>{@code preferenceValue} is a string rather than an enum because
 * {@link PreferenceType#FAVORITE_PAIR} stores a pair symbol (e.g.
 * {@code "EUR/USD"}) which is not in
 * {@code com.fxbrief.report.entity.PreferenceValue}.
 */
public record PreferenceSnapshot(
        PreferenceType preferenceType,
        String preferenceValue
) {}
