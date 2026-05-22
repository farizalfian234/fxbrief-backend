package com.fxbrief.analysis.entity;

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

import java.time.Instant;
import java.util.UUID;

/**
 * Shadow QA row written when the engine runs the per-pair shadow path
 * alongside the mega-call for divergence measurement (DECISIONS D-046).
 * Toggle via {@code fxbrief.analysis.shadow-narrative-logging.enabled}.
 *
 * This table exists for operational measurement only. No business logic
 * reads it. Safe to truncate at any time.
 */
@Entity
@Table(name = "narrative_qa_log")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class NarrativeQaLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fetch_id", nullable = false)
    private UUID fetchId;

    @Column(nullable = false, length = 16)
    private String pair;

    @Column(name = "mega_narrative", columnDefinition = "text")
    private String megaNarrative;

    @Column(name = "per_pair_narrative", columnDefinition = "text")
    private String perPairNarrative;

    @Column(nullable = false)
    private boolean diverged;

    @Column(name = "divergence_reason", length = 255)
    private String divergenceReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
