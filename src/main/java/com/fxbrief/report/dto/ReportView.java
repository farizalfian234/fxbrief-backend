package com.fxbrief.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fxbrief.analysis.dto.NarrowedReportPayload;
import com.fxbrief.analysis.dto.ReportPayload;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

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
 * <h2>Preference fields (Addition 3, D-056)</h2>
 * {@code preferenceSnapshot} and {@code finalDisplayScores} are both
 * {@code @JsonInclude(NON_NULL)} on the record level so generations
 * without an active preference omit them from the wire entirely. When
 * populated:
 * <ul>
 *   <li>{@code preferenceSnapshot} carries the type + value active at
 *       generation time. Immutable for the life of the report.</li>
 *   <li>{@code finalDisplayScores} carries the per-pair score map. The
 *       {@code payload} field was already reordered by these scores
 *       before being passed into the narrower — the map is exposed for
 *       transparency and frontend tooltips, not used by the frontend
 *       for ordering.</li>
 * </ul>
 *
 * <h2>Field meanings</h2>
 * {@code countedAgainstLimit} reflects whether this generation consumed a
 * report credit; {@code reportsExhausted} is {@code true} when the user just
 * hit zero remaining reports as a result of this call. Both fields drive
 * Phase 5A's email trigger and the Phase 6B dashboard messaging.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportView(
        Long reportId,
        String summary,
        Object payload,
        LocalDate forexMarketDate,
        Instant generatedAt,
        String planAtGeneration,
        boolean countedAgainstLimit,
        int remainingReports,
        boolean reportsExhausted,
        PreferenceSnapshot preferenceSnapshot,
        Map<String, Double> finalDisplayScores
) {}
