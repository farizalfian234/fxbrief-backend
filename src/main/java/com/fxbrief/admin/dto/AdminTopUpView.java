package com.fxbrief.admin.dto;

/**
 * Result of a successful admin manual top-up. Mirrors the post-mutation state
 * the admin UI needs to update the user list row in place: the new plan code,
 * the new remaining report count, and whether {@code has_ever_paid} flipped on
 * this call.
 */
public record AdminTopUpView(
        Long userId,
        String plan,
        int remainingReports,
        boolean hasEverPaid,
        boolean planChanged
) {}
