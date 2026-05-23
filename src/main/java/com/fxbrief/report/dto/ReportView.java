package com.fxbrief.report.dto;

import com.fxbrief.analysis.dto.ReportPayload;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Wire shape for {@code POST /reports/generate} and {@code GET /reports/today}.
 *
 * Carries the full Premium-depth {@link ReportPayload} unconditionally — the
 * frontend filters by plan at render time (PRD §8.1, DECISIONS D-042).
 *
 * {@code countedAgainstLimit} reflects whether this generation consumed a
 * report credit; {@code reportsExhausted} is {@code true} when the user just
 * hit zero remaining reports as a result of this call. Both fields drive
 * Phase 5A's email trigger and the Phase 6B dashboard messaging.
 */
public record ReportView(
        Long reportId,
        String summary,
        ReportPayload payload,
        LocalDate forexMarketDate,
        Instant generatedAt,
        String planAtGeneration,
        boolean countedAgainstLimit,
        int remainingReports,
        boolean reportsExhausted
) {}
