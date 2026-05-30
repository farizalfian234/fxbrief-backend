package com.fxbrief.content.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One card in the public weekly-recap list. {@code excerpt} is the first 200
 * characters of the published content.
 */
public record WeeklySummaryListItemView(
        Long id,
        String title,
        String slug,
        LocalDate weekStart,
        LocalDate weekEnd,
        Instant publishedAt,
        String excerpt
) {}
