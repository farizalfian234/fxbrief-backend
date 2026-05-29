package com.fxbrief.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * Row in the history list. Carries only what the table renders — the full
 * payload is fetched on demand via {@code GET /reports/history/{reportId}}.
 *
 * <p>Addition 3 adds {@code preferenceSnapshot} so the history table can
 * render a "Preference" column showing what was active when each report
 * was generated. The field is {@code @JsonInclude(NON_NULL)} at the record
 * level: rows generated without a preference omit it from the wire.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HistoryItemView(
        Long reportId,
        LocalDate forexMarketDate,
        String planAtGeneration,
        String summary,
        PreferenceSnapshot preferenceSnapshot
) {}
