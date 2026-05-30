package com.fxbrief.content.dto;

import java.util.List;

/**
 * Paginated admin weekly-summary list. {@code page} is 1-indexed on the wire.
 */
public record WeeklySummaryListView(
        List<WeeklySummaryRowView> items,
        long totalCount,
        int page,
        int pageSize,
        int totalPages
) {}
