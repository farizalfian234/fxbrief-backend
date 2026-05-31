package com.fxbrief.payment.entity;

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

/**
 * Audit row for every webhook callback received from Midtrans, storing the raw payload
 * as JSONB. {@code processedAt} is stamped once the callback has been verified and
 * applied (or recognised as a duplicate). Kept as a forensic trail independent of the
 * transaction row.
 */
@Entity
@Table(name = "payment_callbacks")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class PaymentCallback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "midtrans_order_id", nullable = false, length = 128)
    private String midtransOrderId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb", nullable = false)
    private String rawPayload;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
