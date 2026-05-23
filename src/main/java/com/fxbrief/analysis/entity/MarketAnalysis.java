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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per fetch cycle, lazily populated on the first user request that
 * lands in that cycle's window. All subsequent users in the same window
 * reuse this row — Claude is NOT called.
 *
 * Per DECISIONS D-044, this is the shared-analysis architecture: Claude cost
 * scales with fetch cycles (~23/day), not with user request count.
 *
 * Phase 3B adds {@code summary} — a short one-line headline composed at
 * write time and copied verbatim onto every {@code user_reports} row that
 * references this analysis.
 */
@Entity
@Table(name = "market_analysis")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class MarketAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fetch_id", nullable = false, unique = true)
    private UUID fetchId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(nullable = false, length = 100)
    private String summary;

    @Column(name = "narrative_mode", nullable = false, length = 32)
    private String narrativeMode;

    @Column(name = "invalid_pair_count", nullable = false)
    private short invalidPairCount;

    @Column(name = "market_data_fetched_at", nullable = false)
    private Instant marketDataFetchedAt;

    @Column(name = "calendar_fetched_at")
    private Instant calendarFetchedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
