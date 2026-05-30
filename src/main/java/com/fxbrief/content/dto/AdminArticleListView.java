package com.fxbrief.content.dto;

import java.util.List;

/**
 * Paginated admin article list. Page size is fixed at 20; {@code page} is
 * 1-indexed, matching the weekly-summary list contract.
 */
public record AdminArticleListView(
        List<AdminArticleRowView> items,
        long totalCount,
        int page,
        int pageSize,
        int totalPages
) {}
