package com.fxbrief.feedback.controller;

import com.fxbrief.common.dto.ApiResponse;
import com.fxbrief.feedback.dto.AdminFeedbackListView;
import com.fxbrief.feedback.dto.AdminFeedbackReplyView;
import com.fxbrief.feedback.dto.ReplyFeedbackRequest;
import com.fxbrief.feedback.service.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin feedback management. Lives in the feedback module (the module that owns
 * the table) but is mounted under {@code /admin} so the
 * {@code requestMatchers("/admin/**").hasRole("ADMIN")} matcher in
 * {@code SecurityConfig} covers it; the class-level {@code @PreAuthorize}
 * mirrors {@code AdminController} for defence in depth.
 */
@RestController
@RequestMapping("/admin/feedback")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminFeedbackController {

    private final FeedbackService feedbackService;

    @GetMapping
    public ResponseEntity<ApiResponse<AdminFeedbackListView>> list(
            @RequestParam(name = "page", defaultValue = "1") int page) {
        return ResponseEntity.ok(ApiResponse.success(feedbackService.list(page)));
    }

    @PostMapping("/{feedbackId}/reply")
    public ResponseEntity<ApiResponse<AdminFeedbackReplyView>> reply(
            @PathVariable Long feedbackId,
            @Valid @RequestBody ReplyFeedbackRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                feedbackService.reply(feedbackId, request)));
    }
}
