package com.fxbrief.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin manual top-up request. {@code plan} must be {@code BASIC} or
 * {@code PREMIUM} (case-insensitive). The phase contract specifies plan
 * change is always tied to a top-up — there is no separate
 * upgrade/downgrade action.
 */
public record AdminTopUpRequest(
        @NotBlank
        @Size(max = 16)
        String plan
) {}
