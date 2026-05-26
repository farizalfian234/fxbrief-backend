package com.fxbrief.report.dto;

import com.fxbrief.analysis.dto.NarrowedReportPayload;
import com.fxbrief.analysis.dto.ReportPayload;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Wire shape for the three single-report endpoints:
 * {@code POST /reports/generate}, {@code GET /reports/today}, and
 * {@code GET /reports/history/{reportId}}.
 *
 * <h2>Payload polymorphism</h2>
 * The {@code payload} field type is {@code Object} so the same envelope can
 * carry either a full {@link ReportPayload} (Premium) or a
 * {@link NarrowedReportPayload} (Free/Basic). Jackson serialises whichever
 * concrete value is set; the {@code @JsonInclude(NON_NULL)} on
 * {@code NarrowedReportPayload} means absent fields are omitted from the
 * wire.
 *
 * Narrowing is owned by {@code ReportPayloadNarrower}; this DTO carries
 * whichever projection the narrower returned. See D-055 for the security
 * rationale.
 *
 * <h2>Field meanings</h2>
 * {@code countedAgainstLimit} reflects whether this generation consumed a
 * report credit; {@code reportsExhausted} is {@code true} when the user just
 * hit zero remaining reports as a result of this call. Both fields drive
 * Phase 5A's email trigger and the Phase 6B dashboard messaging.
 */
public record ReportView(
        Long reportId,
        String summary,
        Object payload,
        LocalDate forexMarketDate,
        Instant generatedAt,
        String planAtGeneration,
        boolean countedAgainstLimit,
        int remainingReports,
        boolean reportsExhausted
) {}
