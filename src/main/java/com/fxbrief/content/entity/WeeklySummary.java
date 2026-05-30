package com.fxbrief.content.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row per market week. The scheduler creates a row in {@code DRAFT} with
 * the Claude-generated recap in {@code claudeDraft}; an admin reviews, edits
 * {@code adminContent}, and publishes. Public endpoints expose
 * {@code adminContent} only.
 */
@Entity
@Table(name = "weekly_summaries")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class WeeklySummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(name = "week_end", nullable = false)
    private LocalDate weekEnd;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, unique = true, length = 255)
    private String slug;

    @Column(name = "claude_draft", columnDefinition = "text")
    private String claudeDraft;

    @Column(name = "admin_content", columnDefinition = "text")
    private String adminContent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WeeklySummaryStatus status;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
