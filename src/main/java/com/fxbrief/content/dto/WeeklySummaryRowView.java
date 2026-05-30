package com.fxbrief.content.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row of the admin weekly-summary list. Carries the metadata the review
 * table renders; full draft and published content are fetched on demand via
 * the detail endpoint.
 */
public record WeeklySummaryRowView(
        Long id,
        String title,
        String slug,
        LocalDate weekStart,
        LocalDate weekEnd,
        String status,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt
) {}
