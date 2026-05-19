package com.fxbrief.auth.dto;

public record CancelDeletionResponse(
        Long userId,
        String email,
        String message
) {}
