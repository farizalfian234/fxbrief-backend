package com.fxbrief.content.service;

import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.content.dto.PublicWeeklySummaryListView;
import com.fxbrief.content.dto.PublicWeeklySummaryView;
import com.fxbrief.content.dto.UpdateWeeklySummaryRequest;
import com.fxbrief.content.dto.WeeklySummaryDetailView;
import com.fxbrief.content.dto.WeeklySummaryListItemView;
import com.fxbrief.content.dto.WeeklySummaryListView;
import com.fxbrief.content.dto.WeeklySummaryRowView;
import com.fxbrief.content.entity.WeeklySummary;
import com.fxbrief.content.entity.WeeklySummaryStatus;
import com.fxbrief.content.repository.WeeklySummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Owns the weekly summary lifecycle: the scheduler-created draft, the admin
 * review/edit/publish/archive surface, and the public read surface. Publishing
 * and archiving keep the sitemap registry in step via {@link SitemapService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklySummaryService {

    static final int PAGE_SIZE = 20;
    private static final int EXCERPT_LENGTH = 200;
    private static final String PATH_PREFIX = "/weekly-recap/";

    private final WeeklySummaryRepository weeklySummaryRepository;
    private final SitemapService sitemapService;

    @Transactional
    public WeeklySummary createDraft(LocalDate weekStart, LocalDate weekEnd,
                                     String title, String slug, String claudeDraft) {
        WeeklySummary summary = new WeeklySummary();
        summary.setWeekStart(weekStart);
        summary.setWeekEnd(weekEnd);
        summary.setTitle(title);
        summary.setSlug(slug);
        summary.setClaudeDraft(claudeDraft);
        summary.setStatus(WeeklySummaryStatus.DRAFT);
        weeklySummaryRepository.save(summary);
        log.info("Weekly summary draft created id={} slug={}", summary.getId(), slug);
        return summary;
    }

    @Transactional(readOnly = true)
    public boolean existsForWeek(LocalDate weekStart) {
        return weeklySummaryRepository.existsByWeekStart(weekStart);
    }

    @Transactional(readOnly = true)
    public WeeklySummaryListView listForAdmin(int page, WeeklySummaryStatus statusFilter) {
        int requestedPage = Math.max(page, 1);
        Pageable pageable = PageRequest.of(requestedPage - 1, PAGE_SIZE,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<WeeklySummary> result = (statusFilter == null)
                ? weeklySummaryRepository.findAll(pageable)
                : weeklySummaryRepository.findByStatus(statusFilter, pageable);

        List<WeeklySummaryRowView> items = result.getContent().stream()
                .map(WeeklySummaryService::toRowView)
                .toList();

        return new WeeklySummaryListView(
                items,
                result.getTotalElements(),
                requestedPage,
                PAGE_SIZE,
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public WeeklySummaryDetailView getForAdmin(Long id) {
        WeeklySummary summary = require(id);
        return new WeeklySummaryDetailView(
                summary.getId(),
                summary.getTitle(),
                summary.getSlug(),
                summary.getWeekStart(),
                summary.getWeekEnd(),
                summary.getClaudeDraft(),
                summary.getAdminContent(),
                summary.getStatus().name(),
                summary.getPublishedAt(),
                summary.getCreatedAt(),
                summary.getUpdatedAt());
    }

    @Transactional
    public WeeklySummaryDetailView update(Long id, UpdateWeeklySummaryRequest request) {
        WeeklySummary summary = require(id);
        summary.setAdminContent(request.adminContent().trim());

        if (request.status() != null && !request.status().isBlank()) {
            WeeklySummaryStatus target = parseStatus(request.status());
            applyStatusTransition(summary, target);
        }

        weeklySummaryRepository.save(summary);
        log.info("Weekly summary updated id={} status={}", id, summary.getStatus());
        return getForAdmin(id);
    }

    @Transactional
    public WeeklySummaryDetailView publish(Long id) {
        WeeklySummary summary = require(id);
        applyStatusTransition(summary, WeeklySummaryStatus.PUBLISHED);
        weeklySummaryRepository.save(summary);
        log.info("Weekly summary published id={} slug={}", id, summary.getSlug());
        return getForAdmin(id);
    }

    @Transactional
    public WeeklySummaryDetailView archive(Long id) {
        WeeklySummary summary = require(id);
        applyStatusTransition(summary, WeeklySummaryStatus.ARCHIVED);
        weeklySummaryRepository.save(summary);
        log.info("Weekly summary archived id={} slug={}", id, summary.getSlug());
        return getForAdmin(id);
    }

    @Transactional(readOnly = true)
    public PublicWeeklySummaryListView listPublished(int page) {
        int requestedPage = Math.max(page, 1);
        Pageable pageable = PageRequest.of(requestedPage - 1, PAGE_SIZE,
                Sort.by(Sort.Direction.DESC, "publishedAt"));

        Page<WeeklySummary> result =
                weeklySummaryRepository.findByStatus(WeeklySummaryStatus.PUBLISHED, pageable);

        List<WeeklySummaryListItemView> items = result.getContent().stream()
                .map(WeeklySummaryService::toListItemView)
                .toList();

        return new PublicWeeklySummaryListView(
                items,
                result.getTotalElements(),
                requestedPage,
                PAGE_SIZE,
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public PublicWeeklySummaryView getPublishedBySlug(String slug) {
        WeeklySummary summary = weeklySummaryRepository
                .findBySlugAndStatus(slug, WeeklySummaryStatus.PUBLISHED)
                .orElseThrow(() -> new DomainException(
                        ErrorCodes.WEEKLY_SUMMARY_NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "Weekly summary not found"));

        return new PublicWeeklySummaryView(
                summary.getId(),
                summary.getTitle(),
                summary.getSlug(),
                summary.getWeekStart(),
                summary.getWeekEnd(),
                summary.getPublishedAt(),
                summary.getAdminContent());
    }

    private void applyStatusTransition(WeeklySummary summary, WeeklySummaryStatus target) {
        if (summary.getStatus() == target) {
            return;
        }
        if (target == WeeklySummaryStatus.PUBLISHED) {
            if (summary.getAdminContent() == null || summary.getAdminContent().isBlank()) {
                throw new DomainException(
                        ErrorCodes.WEEKLY_SUMMARY_NOT_PUBLISHABLE,
                        HttpStatus.CONFLICT,
                        "Weekly summary has no admin content to publish");
            }
            Instant now = Instant.now();
            summary.setStatus(WeeklySummaryStatus.PUBLISHED);
            summary.setPublishedAt(now);
            sitemapService.upsert(pathFor(summary), now);
        } else if (target == WeeklySummaryStatus.ARCHIVED) {
            summary.setStatus(WeeklySummaryStatus.ARCHIVED);
            sitemapService.remove(pathFor(summary));
        } else {
            summary.setStatus(WeeklySummaryStatus.DRAFT);
        }
    }

    private WeeklySummary require(Long id) {
        return weeklySummaryRepository.findById(id)
                .orElseThrow(() -> new DomainException(
                        ErrorCodes.WEEKLY_SUMMARY_NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "Weekly summary not found"));
    }

    private WeeklySummaryStatus parseStatus(String raw) {
        try {
            return WeeklySummaryStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DomainException(
                    ErrorCodes.INVALID_WEEKLY_SUMMARY_STATUS,
                    HttpStatus.BAD_REQUEST,
                    "Status must be one of DRAFT, PUBLISHED, ARCHIVED");
        }
    }

    private String pathFor(WeeklySummary summary) {
        return PATH_PREFIX + summary.getSlug();
    }

    private static WeeklySummaryRowView toRowView(WeeklySummary summary) {
        return new WeeklySummaryRowView(
                summary.getId(),
                summary.getTitle(),
                summary.getSlug(),
                summary.getWeekStart(),
                summary.getWeekEnd(),
                summary.getStatus().name(),
                summary.getPublishedAt(),
                summary.getCreatedAt(),
                summary.getUpdatedAt());
    }

    private static WeeklySummaryListItemView toListItemView(WeeklySummary summary) {
        return new WeeklySummaryListItemView(
                summary.getId(),
                summary.getTitle(),
                summary.getSlug(),
                summary.getWeekStart(),
                summary.getWeekEnd(),
                summary.getPublishedAt(),
                excerpt(summary.getAdminContent()));
    }

    private static String excerpt(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String trimmed = content.strip();
        return trimmed.length() <= EXCERPT_LENGTH
                ? trimmed
                : trimmed.substring(0, EXCERPT_LENGTH);
    }
}
