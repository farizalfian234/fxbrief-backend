package com.fxbrief.feedback.dto;

import java.time.Instant;

/**
 * Acknowledgement returned to the user after submitting feedback.
 */
public record FeedbackSubmittedView(
        Long id,
        Instant createdAt
) {}
