package com.fxbrief.admin.dto;

/**
 * Quick-stats payload for the Admin Dashboard page. Analytics chart endpoints land
 * in Phase 4B and complete the page; this is the static-counter strip at the top.
 */
public record AdminDashboardStatsView(
        long totalActiveSubscribers,
        long totalReportsCurrentForexMonth,
        long totalFreeUsersActive,
        long usersAtZeroRemainingReports
) {}
