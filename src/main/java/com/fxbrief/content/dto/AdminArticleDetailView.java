package com.fxbrief.content.dto;

import java.time.Instant;
import java.util.List;

/**
 * Full admin view of a single article for editing. Carries the author display
 * name rather than {@code author_id}.
 */
public record AdminArticleDetailView(
        Long id,
        String title,
        String slug,
        String content,
        String excerpt,
        String category,
        List<String> tags,
        String featuredImageUrl,
        Short readingTimeMinutes,
        String seoTitle,
        String seoDescription,
        String status,
        Instant scheduledPublishAt,
        Instant publishedAt,
        String authorName,
        Instant createdAt,
        Instant updatedAt
) {}
