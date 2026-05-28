package com.fxbrief.admin.dto;

import java.util.List;

/**
 * Paginated usage-overview response. {@code page} is 1-indexed in the wire
 * contract; internally Spring's {@code Pageable} is zero-indexed.
 */
public record AdminUsageView(
        List<AdminUsageRowView> items,
        long totalCount,
        int page,
        int pageSize,
        int totalPages
) {}
