package com.fxbrief.auth.dto;

public record RegisterResponse(
        Long userId,
        String email,
        String message
) {}
