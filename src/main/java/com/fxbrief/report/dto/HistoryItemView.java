package com.fxbrief.report.dto;

import java.time.LocalDate;

/**
 * Row in the history list. Carries only what the table renders — the full
 * payload is fetched on demand via {@code GET /reports/history/{reportId}}.
 */
public record HistoryItemView(
        Long reportId,
        LocalDate forexMarketDate,
        String planAtGeneration,
        String summary
) {}
