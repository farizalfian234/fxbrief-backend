package com.fxbrief.content.dto;

import java.time.Instant;
import java.util.List;

/**
 * Full published article for the public detail page. Carries no author
 * internal details.
 */
public record PublicArticleView(
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
        Instant publishedAt
) {}
