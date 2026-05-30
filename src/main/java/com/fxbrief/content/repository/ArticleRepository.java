package com.fxbrief.content.repository;

import com.fxbrief.content.entity.Article;
import com.fxbrief.content.entity.ArticleCategory;
import com.fxbrief.content.entity.ArticleStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ArticleRepository extends JpaRepository<Article, Long> {

    boolean existsBySlug(String slug);

    Optional<Article> findBySlugAndStatus(String slug, ArticleStatus status);

    @Query("""
            SELECT a.id AS id, a.title AS title, a.slug AS slug, a.excerpt AS excerpt,
                   a.category AS category, a.tags AS tags, a.featuredImageUrl AS featuredImageUrl,
                   a.readingTimeMinutes AS readingTimeMinutes, a.status AS status,
                   a.scheduledPublishAt AS scheduledPublishAt, a.publishedAt AS publishedAt,
                   u.name AS authorName, a.createdAt AS createdAt, a.updatedAt AS updatedAt
            FROM Article a JOIN User u ON u.id = a.authorId
            WHERE (:status IS NULL OR a.status = :status)
              AND (:category IS NULL OR a.category = :category)
            """)
    Page<AdminArticleRow> findAdminRows(@Param("status") ArticleStatus status,
                                        @Param("category") ArticleCategory category,
                                        Pageable pageable);

    @Query("""
            SELECT a.id AS id, a.title AS title, a.slug AS slug, a.content AS content,
                   a.excerpt AS excerpt, a.category AS category, a.tags AS tags,
                   a.featuredImageUrl AS featuredImageUrl, a.readingTimeMinutes AS readingTimeMinutes,
                   a.seoTitle AS seoTitle, a.seoDescription AS seoDescription, a.status AS status,
                   a.scheduledPublishAt AS scheduledPublishAt, a.publishedAt AS publishedAt,
                   u.name AS authorName, a.createdAt AS createdAt, a.updatedAt AS updatedAt
            FROM Article a JOIN User u ON u.id = a.authorId
            WHERE a.id = :id
            """)
    Optional<AdminArticleDetail> findAdminDetail(@Param("id") Long id);

    @Query(value = """
            SELECT * FROM articles
            WHERE status = 'PUBLISHED'
              AND (CAST(:category AS text) IS NULL OR category = :category)
              AND (CAST(:tag AS text) IS NULL OR :tag = ANY (tags))
            ORDER BY published_at DESC
            """,
            countQuery = """
            SELECT count(*) FROM articles
            WHERE status = 'PUBLISHED'
              AND (CAST(:category AS text) IS NULL OR category = :category)
              AND (CAST(:tag AS text) IS NULL OR :tag = ANY (tags))
            """,
            nativeQuery = true)
    Page<Article> findPublished(@Param("category") String category,
                                @Param("tag") String tag,
                                Pageable pageable);

    List<Article> findByStatusAndScheduledPublishAtLessThanEqual(ArticleStatus status, Instant cutoff);

    @Query("""
            SELECT DISTINCT a.category FROM Article a
            WHERE a.status = com.fxbrief.content.entity.ArticleStatus.PUBLISHED
            ORDER BY a.category ASC
            """)
    List<ArticleCategory> findDistinctPublishedCategories();
}
