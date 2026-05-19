package com.fxbrief.auth.security;

public record AuthenticatedUser(Long id, String email, String role) {}
