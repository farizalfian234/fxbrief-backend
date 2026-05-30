package com.fxbrief.feedback.dto;

import java.time.Instant;

/**
 * Post-reply snapshot so the admin UI can update the row in place.
 */
public record AdminFeedbackReplyView(
        Long id,
        boolean replied,
        Instant repliedAt,
        String replyContent
) {}
