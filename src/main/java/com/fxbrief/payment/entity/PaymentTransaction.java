package com.fxbrief.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One row per Snap transaction created for a top-up. {@code midtransOrderId} is the
 * backend-generated order id ({@code FXBRIEF-{userId}-{timestampMillis}}) and is the
 * reconciliation key against the Midtrans webhook. {@code paidAt} is stamped only when a
 * verified successful webhook is processed.
 */
@Entity
@Table(name = "payment_transactions")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "midtrans_order_id", nullable = false, unique = true, length = 128)
    private String midtransOrderId;

    @Column(nullable = false, length = 16)
    private String plan;

    @Column(name = "amount_usd", nullable = false)
    private BigDecimal amountUsd;

    @Column(name = "amount_idr", nullable = false)
    private long amountIdr;

    @Column(name = "exchange_rate_used", nullable = false)
    private BigDecimal exchangeRateUsed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
