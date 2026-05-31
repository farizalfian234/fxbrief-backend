package com.fxbrief.payment.entity;

/**
 * Lifecycle of a Midtrans transaction as tracked locally.
 *
 * <p>{@code PENDING} is written when the Snap transaction is created. {@code PAID} is
 * written when a webhook confirms a successful settlement/capture. {@code FAILED} covers
 * the terminal unsuccessful Midtrans states (deny, cancel, expire). The local row is
 * advisory: the authoritative payment record lives at Midtrans, and report credit is
 * driven only by a verified successful webhook.
 */
public enum PaymentStatus {
    PENDING,
    PAID,
    FAILED
}
