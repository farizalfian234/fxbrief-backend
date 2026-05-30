package com.fxbrief.content.dto;

import java.time.Instant;
import java.util.List;

/**
 * One row of the admin article list. Carries metadata plus the resolved author
 * name; the full Markdown body is fetched on demand via the detail endpoint.
 */
public record AdminArticleRowView(
        Long id,
        String title,
        String slug,
        String excerpt,
        String category,
        List<String> tags,
        String featuredImageUrl,
        Short readingTimeMinutes,
        String status,
        Instant scheduledPublishAt,
        Instant publishedAt,
        String authorName,
        Instant createdAt,
        Instant updatedAt
) {}
