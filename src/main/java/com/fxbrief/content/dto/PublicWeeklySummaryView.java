package com.fxbrief.content.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Full public weekly recap. Returns {@code content} (the admin-edited copy)
 * only — the Claude draft is never exposed publicly.
 */
public record PublicWeeklySummaryView(
        Long id,
        String title,
        String slug,
        LocalDate weekStart,
        LocalDate weekEnd,
        Instant publishedAt,
        String content
) {}
