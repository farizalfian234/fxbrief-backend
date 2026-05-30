package com.fxbrief.content.dto;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Update-article request body. Every field is optional; a {@code null} field is
 * left unchanged. {@code title} and {@code content}, when present, must be
 * non-blank (enforced in the service). {@code category} and {@code status},
 * when present, are validated against their enums.
 */
public record UpdateArticleRequest(
        @Size(max = 255) String title,
        @Size(max = 255) String slug,
        String content,
        @Size(max = 500) String excerpt,
        String category,
        List<@Size(max = 64) String> tags,
        @Size(max = 500) String featuredImageUrl,
        @Size(max = 255) String seoTitle,
        @Size(max = 500) String seoDescription,
        Instant scheduledPublishAt,
        String status
) {}
