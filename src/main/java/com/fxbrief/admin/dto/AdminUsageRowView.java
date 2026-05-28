package com.fxbrief.admin.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row of the Admin Usage Overview page. Columns mirror PRD §9.2:
 * forex market date, the user (name + email), the plan code frozen at
 * generation time, whether this report counted against the user's limit
 * (false on the zero-content "markets consolidating" case, per PRD §5.5),
 * and the exact generation timestamp.
 */
public record AdminUsageRowView(
        Long reportId,
        Long userId,
        String userEmail,
        String userName,
        LocalDate forexMarketDate,
        String planAtGeneration,
        boolean countedAgainstLimit,
        Instant generatedAt
) {}
