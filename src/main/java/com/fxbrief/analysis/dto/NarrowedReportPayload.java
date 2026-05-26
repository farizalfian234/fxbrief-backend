package com.fxbrief.analysis.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Trimmed report payload returned to Free and Basic plan users.
 *
 * <p>Two flavours of this shape exist on the wire, distinguished by which
 * fields are present:
 *
 * <ul>
 *   <li><b>Live dashboard</b> (POST /reports/generate, GET /reports/today):
 *       {@code bestPairView} populated, {@code compactPreviews} populated with
 *       up to three entries to drive the "Additional Market Coverage —
 *       Unlock Premium" upsell.</li>
 *   <li><b>Archived history</b> (GET /reports/history/{reportId}):
 *       {@code bestPairView} populated, {@code compactPreviews} is null and
 *       omitted from the response. Per the "clean reading experience" rule
 *       no upsell affordance is exposed inside archived report content.</li>
 * </ul>
 *
 * <p>The consolidating-markets case (engine produced
 * {@code marketsConsolidating=true} with no pairs) is represented by
 * {@code bestPairView = null} and {@code compactPreviews = null} or empty;
 * the frontend already renders this state from the {@code marketsConsolidating}
 * flag alone.
 *
 * <p>The stored {@code market_analysis.payload} JSONB is never mutated —
 * narrowing is a read-time projection. Premium users continue to receive the
 * full {@link ReportPayload} on the same endpoints.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NarrowedReportPayload(
        String bestPair,
        BestPairView bestPairView,
        List<CompactPairPreview> compactPreviews,
        boolean marketsConsolidating,
        Instant generatedAt,
        Instant marketDataFetchedAt
) {}
