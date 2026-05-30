package com.fxbrief.feedback.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * One row of the admin feedback list. {@code replied} is the at-a-glance flag
 * the UI uses to show replied / not-replied; {@code repliedAt} and
 * {@code replyContent} are null and omitted when the feedback is still pending.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminFeedbackRowView(
        Long id,
        Long userId,
        String userName,
        String userEmail,
        String content,
        Instant submittedAt,
        boolean replied,
        Instant repliedAt,
        String replyContent
) {}
