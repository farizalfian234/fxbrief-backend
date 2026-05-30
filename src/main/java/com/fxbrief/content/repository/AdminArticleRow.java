package com.fxbrief.content.repository;

import com.fxbrief.content.entity.ArticleCategory;
import com.fxbrief.content.entity.ArticleStatus;

import java.time.Instant;
import java.util.List;

/**
 * Read projection for the admin article list. Joins the author's display name
 * from {@code users} so the list response carries {@code authorName} rather
 * than the raw {@code author_id}. The list omits the full Markdown body.
 */
public interface AdminArticleRow {

    Long getId();

    String getTitle();

    String getSlug();

    String getExcerpt();

    ArticleCategory getCategory();

    List<String> getTags();

    String getFeaturedImageUrl();

    Short getReadingTimeMinutes();

    ArticleStatus getStatus();

    Instant getScheduledPublishAt();

    Instant getPublishedAt();

    String getAuthorName();

    Instant getCreatedAt();

    Instant getUpdatedAt();
}
