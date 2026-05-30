package com.fxbrief.content.service;

import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.content.dto.AdminArticleDetailView;
import com.fxbrief.content.dto.AdminArticleListView;
import com.fxbrief.content.dto.AdminArticleRowView;
import com.fxbrief.content.dto.CreateArticleRequest;
import com.fxbrief.content.dto.PublicArticleListItemView;
import com.fxbrief.content.dto.PublicArticleListView;
import com.fxbrief.content.dto.PublicArticleView;
import com.fxbrief.content.dto.UpdateArticleRequest;
import com.fxbrief.content.entity.Article;
import com.fxbrief.content.entity.ArticleCategory;
import com.fxbrief.content.entity.ArticleStatus;
import com.fxbrief.content.repository.AdminArticleDetail;
import com.fxbrief.content.repository.AdminArticleRow;
import com.fxbrief.content.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Owns the article lifecycle: admin create/edit/publish/archive/delete and the
 * public read surface. Publish and archive keep the sitemap registry in step via
 * {@link SitemapService}. Reading time is derived from the Markdown body at save
 * time at 200 words per minute.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleService {

    static final int PAGE_SIZE = 20;
    private static final int WORDS_PER_MINUTE = 200;
    private static final String PATH_PREFIX = "/articles/";
    private static final String CHANGE_FREQ = "monthly";
    private static final BigDecimal PRIORITY = new BigDecimal("0.8");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final ArticleRepository articleRepository;
    private final SitemapService sitemapService;
    private final SlugGenerator slugGenerator;

    @Transactional(readOnly = true)
    public AdminArticleListView listForAdmin(int page, String statusFilter, String categoryFilter) {
        ArticleStatus status = parseStatusFilter(statusFilter);
        ArticleCategory category = parseCategoryFilter(categoryFilter);

        int requestedPage = Math.max(page, 1);
        Pageable pageable = PageRequest.of(requestedPage - 1, PAGE_SIZE,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<AdminArticleRow> result = articleRepository.findAdminRows(status, category, pageable);

        List<AdminArticleRowView> items = result.getContent().stream()
                .map(ArticleService::toRowView)
                .toList();

        return new AdminArticleListView(
                items,
                result.getTotalElements(),
                requestedPage,
                PAGE_SIZE,
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public AdminArticleDetailView getForAdmin(Long id) {
        AdminArticleDetail row = articleRepository.findAdminDetail(id)
                .orElseThrow(ArticleService::notFound);
        return toDetailView(row);
    }

    @Transactional
    public AdminArticleDetailView create(CreateArticleRequest request, Long authorId) {
        ArticleCategory category = parseCategory(request.category());
        ArticleStatus status = (request.status() == null || request.status().isBlank())
                ? ArticleStatus.DRAFT
                : parseStatus(request.status());

        Article article = new Article();
        article.setTitle(request.title().trim());
        article.setSlug(resolveSlug(request.slug(), request.title(), null));
        article.setContent(request.content());
        article.setExcerpt(trimToNull(request.excerpt()));
        article.setCategory(category);
        article.setTags(normalizeTags(request.tags()));
        article.setFeaturedImageUrl(trimToNull(request.featuredImageUrl()));
        article.setReadingTimeMinutes(readingTime(request.content()));
        article.setSeoTitle(trimToNull(request.seoTitle()));
        article.setSeoDescription(trimToNull(request.seoDescription()));
        article.setScheduledPublishAt(request.scheduledPublishAt());
        article.setAuthorId(authorId);
        article.setStatus(status);

        if (status == ArticleStatus.PUBLISHED) {
            stampPublished(article);
        }

        articleRepository.save(article);
        log.info("Article created id={} slug={} status={}", article.getId(), article.getSlug(), status);
        return getForAdmin(article.getId());
    }

    @Transactional
    public AdminArticleDetailView update(Long id, UpdateArticleRequest request) {
        Article article = require(id);

        if (request.title() != null) {
            if (request.title().isBlank()) {
                throw validation("title must not be blank");
            }
            article.setTitle(request.title().trim());
        }
        if (request.slug() != null && !request.slug().isBlank()) {
            article.setSlug(resolveSlug(request.slug(), article.getTitle(), article.getId()));
        }
        if (request.content() != null) {
            if (request.content().isBlank()) {
                throw validation("content must not be blank");
            }
            article.setContent(request.content());
            article.setReadingTimeMinutes(readingTime(request.content()));
        }
        if (request.excerpt() != null) {
            article.setExcerpt(trimToNull(request.excerpt()));
        }
        if (request.category() != null) {
            article.setCategory(parseCategory(request.category()));
        }
        if (request.tags() != null) {
            article.setTags(normalizeTags(request.tags()));
        }
        if (request.featuredImageUrl() != null) {
            article.setFeaturedImageUrl(trimToNull(request.featuredImageUrl()));
        }
        if (request.seoTitle() != null) {
            article.setSeoTitle(trimToNull(request.seoTitle()));
        }
        if (request.seoDescription() != null) {
            article.setSeoDescription(trimToNull(request.seoDescription()));
        }
        if (request.scheduledPublishAt() != null) {
            article.setScheduledPublishAt(request.scheduledPublishAt());
        }
        if (request.status() != null && !request.status().isBlank()) {
            applyStatusTransition(article, parseStatus(request.status()));
        }

        articleRepository.save(article);
        log.info("Article updated id={} status={}", id, article.getStatus());
        return getForAdmin(id);
    }

    @Transactional
    public AdminArticleDetailView publish(Long id) {
        Article article = require(id);
        transitionToPublished(article);
        articleRepository.save(article);
        log.info("Article published id={} slug={}", id, article.getSlug());
        return getForAdmin(id);
    }

    @Transactional
    public AdminArticleDetailView archive(Long id) {
        Article article = require(id);
        transitionToArchived(article);
        articleRepository.save(article);
        log.info("Article archived id={} slug={}", id, article.getSlug());
        return getForAdmin(id);
    }

    @Transactional
    public void delete(Long id) {
        Article article = require(id);
        if (article.getStatus() != ArticleStatus.DRAFT) {
            throw new DomainException(
                    ErrorCodes.ARTICLE_DELETE_NOT_ALLOWED,
                    HttpStatus.BAD_REQUEST,
                    "Only DRAFT articles can be deleted; archive a published article instead");
        }
        articleRepository.delete(article);
        log.info("Article deleted id={} slug={}", id, article.getSlug());
    }

    @Transactional(readOnly = true)
    public PublicArticleListView listPublished(int page, String categoryFilter, String tag) {
        ArticleCategory category = parseCategoryFilter(categoryFilter);
        String tagFilter = (tag == null || tag.isBlank()) ? null : tag.trim();

        int requestedPage = Math.max(page, 1);
        Pageable pageable = PageRequest.of(requestedPage - 1, PAGE_SIZE);

        Page<Article> result = articleRepository.findPublished(
                category == null ? null : category.name(), tagFilter, pageable);

        List<PublicArticleListItemView> items = result.getContent().stream()
                .map(ArticleService::toPublicListItem)
                .toList();

        return new PublicArticleListView(
                items,
                result.getTotalElements(),
                requestedPage,
                PAGE_SIZE,
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public PublicArticleView getPublishedBySlug(String slug) {
        Article article = articleRepository.findBySlugAndStatus(slug, ArticleStatus.PUBLISHED)
                .orElseThrow(ArticleService::notFound);
        return new PublicArticleView(
                article.getId(),
                article.getTitle(),
                article.getSlug(),
                article.getContent(),
                article.getExcerpt(),
                article.getCategory().name(),
                article.getTags(),
                article.getFeaturedImageUrl(),
                article.getReadingTimeMinutes(),
                article.getSeoTitle(),
                article.getSeoDescription(),
                article.getPublishedAt());
    }

    @Transactional(readOnly = true)
    public List<String> listPublishedCategories() {
        return articleRepository.findDistinctPublishedCategories().stream()
                .map(ArticleCategory::name)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Long> findScheduledDue(Instant now) {
        return articleRepository
                .findByStatusAndScheduledPublishAtLessThanEqual(ArticleStatus.SCHEDULED, now)
                .stream()
                .map(Article::getId)
                .toList();
    }

    @Transactional
    public void publishScheduled(Long id) {
        Article article = articleRepository.findById(id).orElse(null);
        if (article == null || article.getStatus() != ArticleStatus.SCHEDULED) {
            return;
        }
        stampPublished(article);
        articleRepository.save(article);
        log.info("Article auto-published by scheduler id={} slug={}", id, article.getSlug());
    }

    private void applyStatusTransition(Article article, ArticleStatus target) {
        if (article.getStatus() == target) {
            return;
        }
        switch (target) {
            case PUBLISHED -> transitionToPublished(article);
            case ARCHIVED -> transitionToArchived(article);
            case DRAFT, SCHEDULED -> article.setStatus(target);
        }
    }

    private void transitionToPublished(Article article) {
        boolean wasPublished = article.getStatus() == ArticleStatus.PUBLISHED;
        stampPublished(article);
        if (!wasPublished) {
            log.debug("Article {} entered PUBLISHED", article.getId());
        }
    }

    private void stampPublished(Article article) {
        Instant now = Instant.now();
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setPublishedAt(now);
        article.setScheduledPublishAt(null);
        sitemapService.upsert(pathFor(article), now, CHANGE_FREQ, PRIORITY);
    }

    private void transitionToArchived(Article article) {
        article.setStatus(ArticleStatus.ARCHIVED);
        sitemapService.remove(pathFor(article));
    }

    private String resolveSlug(String requestedSlug, String title, Long currentId) {
        String base = (requestedSlug != null && !requestedSlug.isBlank())
                ? slugGenerator.slugify(requestedSlug)
                : slugGenerator.slugify(title);
        if (base.isEmpty()) {
            throw validation("slug could not be derived from the title; provide an explicit slug");
        }
        if (articleRepository.existsBySlug(base) && !isOwnSlug(base, currentId)) {
            throw new DomainException(
                    ErrorCodes.ARTICLE_SLUG_CONFLICT,
                    HttpStatus.CONFLICT,
                    "An article with this slug already exists");
        }
        return base;
    }

    private boolean isOwnSlug(String slug, Long currentId) {
        if (currentId == null) {
            return false;
        }
        return articleRepository.findById(currentId)
                .map(existing -> slug.equals(existing.getSlug()))
                .orElse(false);
    }

    private Short readingTime(String content) {
        if (content == null || content.isBlank()) {
            return 1;
        }
        int words = WHITESPACE.split(content.strip()).length;
        int minutes = (int) Math.ceil(words / (double) WORDS_PER_MINUTE);
        return (short) Math.max(minutes, 1);
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return null;
        }
        List<String> cleaned = tags.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private Article require(Long id) {
        return articleRepository.findById(id).orElseThrow(ArticleService::notFound);
    }

    private ArticleStatus parseStatus(String raw) {
        try {
            return ArticleStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DomainException(
                    ErrorCodes.INVALID_ARTICLE_STATUS,
                    HttpStatus.BAD_REQUEST,
                    "Status must be one of DRAFT, SCHEDULED, PUBLISHED, ARCHIVED");
        }
    }

    private ArticleStatus parseStatusFilter(String raw) {
        return (raw == null || raw.isBlank()) ? null : parseStatus(raw);
    }

    private ArticleCategory parseCategory(String raw) {
        try {
            return ArticleCategory.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DomainException(
                    ErrorCodes.INVALID_ARTICLE_CATEGORY,
                    HttpStatus.BAD_REQUEST,
                    "Category must be one of WEEKLY_RECAP, EDUCATIONAL, FOREX_BASICS, "
                            + "MACRO_INSIGHTS, PLATFORM_UPDATES, TRADING_PSYCHOLOGY");
        }
    }

    private ArticleCategory parseCategoryFilter(String raw) {
        return (raw == null || raw.isBlank()) ? null : parseCategory(raw);
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCodes.ARTICLE_NOT_FOUND, HttpStatus.NOT_FOUND, "Article not found");
    }

    private static DomainException validation(String message) {
        return new DomainException(ErrorCodes.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, message);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String pathFor(Article article) {
        return PATH_PREFIX + article.getSlug();
    }

    private static AdminArticleRowView toRowView(AdminArticleRow row) {
        return new AdminArticleRowView(
                row.getId(),
                row.getTitle(),
                row.getSlug(),
                row.getExcerpt(),
                row.getCategory().name(),
                row.getTags(),
                row.getFeaturedImageUrl(),
                row.getReadingTimeMinutes(),
                row.getStatus().name(),
                row.getScheduledPublishAt(),
                row.getPublishedAt(),
                row.getAuthorName(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private static AdminArticleDetailView toDetailView(AdminArticleDetail row) {
        return new AdminArticleDetailView(
                row.getId(),
                row.getTitle(),
                row.getSlug(),
                row.getContent(),
                row.getExcerpt(),
                row.getCategory().name(),
                row.getTags(),
                row.getFeaturedImageUrl(),
                row.getReadingTimeMinutes(),
                row.getSeoTitle(),
                row.getSeoDescription(),
                row.getStatus().name(),
                row.getScheduledPublishAt(),
                row.getPublishedAt(),
                row.getAuthorName(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private static PublicArticleListItemView toPublicListItem(Article article) {
        return new PublicArticleListItemView(
                article.getId(),
                article.getTitle(),
                article.getSlug(),
                article.getExcerpt(),
                article.getCategory().name(),
                article.getTags(),
                article.getFeaturedImageUrl(),
                article.getReadingTimeMinutes(),
                article.getPublishedAt());
    }
}
