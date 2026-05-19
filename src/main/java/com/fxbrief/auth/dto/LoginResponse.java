package com.fxbrief.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        String token,
        String tokenType,
        Instant expiresAt,
        Long userId,
        String email,
        String name,
        String role,
        boolean deletionPending,
        Instant deletionDate
) {}
