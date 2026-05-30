package com.fxbrief.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /feedback}. {@code content} is required and bounded to
 * a generous upper limit to reject obviously abusive payloads while leaving
 * room for genuine free-form feedback.
 */
public record SubmitFeedbackRequest(
        @NotBlank @Size(max = 5000) String content
) {}
