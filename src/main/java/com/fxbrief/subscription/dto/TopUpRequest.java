package com.fxbrief.subscription.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TopUpRequest(
        @NotBlank @Size(max = 16) String plan
) {}
