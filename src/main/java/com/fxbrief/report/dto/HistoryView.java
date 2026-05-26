package com.fxbrief.report.dto;

import java.util.List;

/**
 * Wire shape for {@code GET /reports/history}.
 *
 * Free users and Basic/Premium users whose effective plan has lapsed to Free
 * receive {@code locked = true} with an empty {@code items} list — the
 * frontend renders the blurred-table upsell. Active Basic users receive
 * their latest 10 archived rows together with {@code totalArchivedCount} so
 * the frontend can decide whether to show the "X older reports available
 * in Premium" message (PRD §9.2). Active Premium users receive
 * page-by-page access to the full archive.
 *
 * {@code page} is 1-indexed in the wire contract; internally Spring's
 * {@code Pageable} is zero-indexed.
 */
public record HistoryView(
        boolean locked,
        List<HistoryItemView> items,
        long totalArchivedCount,
        int page,
        int pageSize,
        int totalPages
) {}
