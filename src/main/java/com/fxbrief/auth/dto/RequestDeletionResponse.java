package com.fxbrief.auth.dto;

import java.time.Instant;

public record RequestDeletionResponse(
        Long userId,
        String email,
        Instant deletionRequestedAt,
        Instant deletionDate,
        String message
) {}
