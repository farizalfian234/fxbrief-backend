package com.fxbrief.analysis.dto;

import java.time.Instant;
import java.util.List;

/**
 * Full report output. The engine always produces a Premium-depth payload —
 * plan-based content filtering happens at render time in the frontend, not in
 * the engine (see DECISIONS D-042). The {@code bestPair} pointer is computed
 * regardless so a Basic-view client can render a "headline pair" without
 * re-running ranking logic.
 */
public record ReportPayload(
        String bestPair,
        List<PairAnalysis> pairs,
        boolean marketsConsolidating,
        Instant generatedAt,
        Instant marketDataFetchedAt,
        Instant calendarFetchedAt
) {}
