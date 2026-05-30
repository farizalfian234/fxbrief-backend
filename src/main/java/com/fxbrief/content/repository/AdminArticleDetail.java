package com.fxbrief.content.repository;

import com.fxbrief.content.entity.ArticleCategory;
import com.fxbrief.content.entity.ArticleStatus;

import java.time.Instant;
import java.util.List;

/**
 * Read projection for the admin article detail view. Carries the full article
 * for editing plus the resolved author display name (not {@code author_id}).
 */
public interface AdminArticleDetail {

    Long getId();

    String getTitle();

    String getSlug();

    String getContent();

    String getExcerpt();

    ArticleCategory getCategory();

    List<String> getTags();

    String getFeaturedImageUrl();

    Short getReadingTimeMinutes();

    String getSeoTitle();

    String getSeoDescription();

    ArticleStatus getStatus();

    Instant getScheduledPublishAt();

    Instant getPublishedAt();

    String getAuthorName();

    Instant getCreatedAt();

    Instant getUpdatedAt();
}
