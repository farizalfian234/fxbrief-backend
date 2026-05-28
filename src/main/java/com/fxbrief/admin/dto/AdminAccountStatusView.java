package com.fxbrief.admin.dto;

/**
 * Result of an activate or deactivate action — the post-mutation value of
 * {@code users.is_active} so the admin UI can update the row in place.
 */
public record AdminAccountStatusView(
        Long userId,
        boolean active
) {}
