package com.fxbrief.report.entity;

import com.fxbrief.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The currently-active preference for a user. At most one row per user
 * (enforced by the UNIQUE on user_id in V10). Absence of a row means
 * "no preference set" — the report flow then skips compatibility scoring.
 *
 * <p>{@code preferenceValue} is stored as a free-form string rather than an
 * enum column because {@link PreferenceType#FAVORITE_PAIR} stores a pair
 * symbol (e.g. {@code "EUR/USD"}) which is not enumerated in
 * {@link PreferenceValue}. The {@code PreferenceResolver} validates the
 * (type, value) pair on every read.
 */
@Entity
@Table(name = "user_preferences")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class UserPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "preference_type", nullable = false, length = 32)
    private PreferenceType preferenceType;

    @Column(name = "preference_value", nullable = false, length = 32)
    private String preferenceValue;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }
}
