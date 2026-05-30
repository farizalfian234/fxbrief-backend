package com.fxbrief.content.dto;

import java.time.Instant;
import java.util.List;

/**
 * One row of the public article list.
 */
public record PublicArticleListItemView(
        Long id,
        String title,
        String slug,
        String excerpt,
        String category,
        List<String> tags,
        String featuredImageUrl,
        Short readingTimeMinutes,
        Instant publishedAt
) {}
