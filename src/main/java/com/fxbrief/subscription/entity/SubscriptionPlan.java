package com.fxbrief.subscription.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "subscription_plans")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class SubscriptionPlan {

    @Id
    private Short id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(name = "report_count", nullable = false)
    private Integer reportCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
