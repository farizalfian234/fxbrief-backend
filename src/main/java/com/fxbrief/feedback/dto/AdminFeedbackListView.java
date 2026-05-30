package com.fxbrief.feedback.dto;

import java.util.List;

/**
 * Paginated admin feedback list. Mirrors the pagination shape used by the
 * other admin list views ({@code AdminUserListView}, {@code AdminUsageView}):
 * 1-indexed {@code page}, fixed {@code pageSize} of 20.
 */
public record AdminFeedbackListView(
        List<AdminFeedbackRowView> items,
        long totalCount,
        int page,
        int pageSize,
        int totalPages
) {}
