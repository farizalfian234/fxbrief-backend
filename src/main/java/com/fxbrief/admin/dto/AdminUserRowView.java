package com.fxbrief.admin.dto;

import java.time.Instant;

/**
 * One row of the Admin Users page. {@code plan} is the user's base persisted
 * plan code ({@code FREE} / {@code BASIC} / {@code PREMIUM}); the admin panel
 * displays the base plan directly — the effective-plan lapse projection is a
 * user-side rendering concern and is not exposed here.
 *
 * {@code lastGeneratedAt} is the most recent {@code generated_at} across every
 * {@code user_reports} row for this user (archived or live), or {@code null}
 * if the user has never generated a report.
 *
 * {@code deletionRequestedAt} is non-null only when the user has an in-flight
 * deletion request — the admin UI uses its presence to render the "pending
 * deletion" indicator. Admins cannot cancel deletion requests; that is a
 * user-only action.
 */
public record AdminUserRowView(
        Long id,
        String email,
        String name,
        String plan,
        int remainingReports,
        boolean active,
        boolean hasEverPaid,
        Instant deletionRequestedAt,
        Instant lastGeneratedAt
) {}
