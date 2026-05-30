package com.fxbrief.content.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Full admin view of a single weekly summary, including both the Claude draft
 * and the admin-edited content. Admin-only — the public detail view never
 * carries {@code claudeDraft}.
 */
public record WeeklySummaryDetailView(
        Long id,
        String title,
        String slug,
        LocalDate weekStart,
        LocalDate weekEnd,
        String claudeDraft,
        String adminContent,
        String status,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt
) {}
