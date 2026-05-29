package com.fxbrief.report.entity;

import com.fxbrief.analysis.entity.MarketAnalysis;
import com.fxbrief.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row per user per forex market day. References the shared
 * market_analysis row that produced its content — no content
 * duplication. Once archived, the row is immutable; plan_at_generation
 * is the badge frozen at write time so a later upgrade does not
 * retroactively change historical displays.
 *
 * <p>Addition 3 adds {@code preferenceSnapshot} and {@code finalDisplayScores}
 * — both nullable JSONB columns. When the user generated this report with
 * an active preference (override or persisted), both fields are populated
 * and immutable thereafter. Changing {@code user_preferences} later never
 * rewrites these. See DECISIONS D-056.
 */
@Entity
@Table(name = "user_reports")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class UserReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "market_analysis_id", nullable = false)
    private MarketAnalysis marketAnalysis;

    @Column(nullable = false, length = 100)
    private String summary;

    @Column(name = "forex_market_date", nullable = false)
    private LocalDate forexMarketDate;

    @Column(name = "counted_against_limit", nullable = false)
    private boolean countedAgainstLimit;

    @Column(name = "plan_at_generation", nullable = false)
    private short planAtGeneration;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "is_archived", nullable = false)
    private boolean archived;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preference_snapshot", columnDefinition = "jsonb")
    private String preferenceSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "final_display_scores", columnDefinition = "jsonb")
    private String finalDisplayScores;
}
