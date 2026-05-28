package com.fxbrief.admin.dto;

import java.util.List;

/**
 * Paginated user list response for the Admin Users page. {@code page} is
 * 1-indexed in the wire contract; internally Spring's {@code Pageable} is
 * zero-indexed.
 */
public record AdminUserListView(
        List<AdminUserRowView> items,
        long totalCount,
        int page,
        int pageSize,
        int totalPages
) {}
