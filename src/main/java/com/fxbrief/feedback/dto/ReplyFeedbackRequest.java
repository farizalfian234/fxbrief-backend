package com.fxbrief.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /admin/feedback/{feedbackId}/reply}. The feedback id is
 * a path variable; this carries only the reply text.
 */
public record ReplyFeedbackRequest(
        @NotBlank @Size(max = 5000) String replyContent
) {}
