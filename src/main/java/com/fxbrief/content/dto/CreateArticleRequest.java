package com.fxbrief.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Create-article request body. {@code slug} is optional — when blank it is
 * derived from the title. {@code category} and {@code status} are validated in
 * the service against their enums so the response carries a specific error code.
 */
public record CreateArticleRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 255) String slug,
        @NotBlank String content,
        @Size(max = 500) String excerpt,
        @NotBlank String category,
        List<@Size(max = 64) String> tags,
        @Size(max = 500) String featuredImageUrl,
        @Size(max = 255) String seoTitle,
        @Size(max = 500) String seoDescription,
        Instant scheduledPublishAt,
        String status
) {}
