package com.fxbrief.content.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Promotes scheduled articles to published. Runs every 15 minutes, finds
 * articles in SCHEDULED status whose {@code scheduled_publish_at} has passed,
 * and publishes each one — stamping {@code published_at} and upserting its
 * sitemap entry.
 *
 * <p>Each article is published in its own transaction so a single failure does
 * not abandon the rest of the batch. Re-running is idempotent: an article that
 * already left SCHEDULED is skipped.
 *
 * <p>Single-instance assumption applies (BUILD_ASSUMPTIONS D2). The
 * status guard makes a concurrent second fire a no-op for any row already
 * promoted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticlePublishScheduler {

    private final ArticleService articleService;
    private final Clock clock = Clock.systemUTC();

    @Scheduled(cron = "${fxbrief.scheduler.article-publish-cron:0 0/15 * * * *}", zone = "UTC")
    public void publishDueArticles() {
        Instant now = Instant.now(clock);
        List<Long> due = articleService.findScheduledDue(now);
        if (due.isEmpty()) {
            return;
        }
        log.info("Publishing {} scheduled article(s) due at or before {}", due.size(), now);
        for (Long id : due) {
            try {
                articleService.publishScheduled(id);
            } catch (Exception e) {
                log.error("Failed to auto-publish scheduled article id={}: {}", id, e.getMessage(), e);
            }
        }
    }
}
