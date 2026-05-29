package com.fxbrief.report.service;

import com.fxbrief.analysis.dto.PairAnalysis;
import com.fxbrief.analysis.dto.ReportPayload;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Applies a stored {@code final_display_scores} map to a freshly-deserialised
 * {@link ReportPayload}, producing a new payload whose {@code pairs} list is
 * ordered by score descending and whose {@code bestPair} pointer is the
 * top-scored pair.
 *
 * <p>This is invoked on every read of a stored report that had a preference
 * snapshot at generation time:
 * <ul>
 *   <li>{@code GET /reports/today} — read user_reports.final_display_scores,
 *       apply over the deserialised market_analysis.payload.</li>
 *   <li>{@code GET /reports/history/{reportId}} — same, for archived rows.</li>
 * </ul>
 *
 * <p>Per DECISIONS D-056, the shared {@code market_analysis.payload} JSONB
 * is never modified — reordering is always a fresh per-user projection.
 * This keeps the shared row safe for concurrent reuse by other users with
 * different preferences (or none).
 *
 * <p>Pairs absent from the scores map (defensive guard against a stale
 * snapshot referencing a pair the engine didn't analyse) keep their original
 * position relative to other absent pairs and sort after all scored pairs.
 */
@Component
public class PayloadReorderer {

    /**
     * @return a new {@link ReportPayload} with pairs reordered and {@code bestPair}
     *         rewritten. Returns the input unchanged when {@code scores} is null
     *         or empty (defensive — caller normally checks before invoking).
     */
    public ReportPayload reorder(ReportPayload payload, Map<String, Double> scores) {
        if (payload == null) {
            return null;
        }
        if (scores == null || scores.isEmpty() || payload.pairs() == null
                || payload.pairs().isEmpty()) {
            return payload;
        }

        List<PairAnalysis> sorted = new ArrayList<>(payload.pairs());
        sorted.sort(Comparator.comparingDouble(
                (PairAnalysis p) -> scores.getOrDefault(p.pair(), Double.NEGATIVE_INFINITY))
                .reversed());

        String newBestPair = sorted.isEmpty() ? null : sorted.get(0).pair();

        return new ReportPayload(
                newBestPair,
                sorted,
                payload.marketsConsolidating(),
                payload.generatedAt(),
                payload.marketDataFetchedAt(),
                payload.calendarFetchedAt());
    }
}
