package com.fxbrief.content.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Public sitemap registry. One row per public path. Populated when content is
 * published and removed when it is archived; the Phase 6A SSR build reads the
 * full set to generate sitemap.xml.
 */
@Entity
@Table(name = "sitemap_entries")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class SitemapEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 500)
    private String path;

    @Column(name = "last_modified", nullable = false)
    private Instant lastModified;

    @Column(name = "change_freq", nullable = false, length = 20)
    private String changeFreq;

    @Column(nullable = false, precision = 2, scale = 1)
    private BigDecimal priority;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
