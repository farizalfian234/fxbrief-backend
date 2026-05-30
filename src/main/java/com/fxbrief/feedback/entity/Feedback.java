package com.fxbrief.feedback.entity;

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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A single feedback submission from a user. Created via the user-side submit
 * endpoint and replied to from the admin panel. Once replied, {@code status}
 * is {@link FeedbackStatus#REPLIED} and {@code repliedAt} / {@code replyContent}
 * are populated.
 *
 * <p>No email is sent in this phase — the user thank-you / admin notification
 * and the reply email are Phase 5A concerns.
 */
@Entity
@Table(name = "feedback")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FeedbackStatus status;

    @Column(name = "replied_at")
    private Instant repliedAt;

    @Column(name = "reply_content", columnDefinition = "TEXT")
    private String replyContent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        if (this.status == null) {
            this.status = FeedbackStatus.PENDING;
        }
    }
}
