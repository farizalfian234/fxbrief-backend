package com.fxbrief.content.dto;

import java.util.List;

/**
 * Paginated public list of published articles. Page size fixed at 20;
 * {@code page} is 1-indexed.
 */
public record PublicArticleListView(
        List<PublicArticleListItemView> items,
        long totalCount,
        int page,
        int pageSize,
        int totalPages
) {}
