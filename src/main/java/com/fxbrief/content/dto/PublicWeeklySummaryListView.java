package com.fxbrief.content.dto;

import java.util.List;

/**
 * Paginated public list of published weekly recaps. {@code page} is 1-indexed
 * on the wire.
 */
public record PublicWeeklySummaryListView(
        List<WeeklySummaryListItemView> items,
        long totalCount,
        int page,
        int pageSize,
        int totalPages
) {}
