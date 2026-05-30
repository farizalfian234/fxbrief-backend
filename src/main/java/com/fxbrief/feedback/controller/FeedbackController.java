package com.fxbrief.feedback.controller;

import com.fxbrief.auth.security.AuthenticatedUser;
import com.fxbrief.common.dto.ApiResponse;
import com.fxbrief.feedback.dto.FeedbackSubmittedView;
import com.fxbrief.feedback.dto.SubmitFeedbackRequest;
import com.fxbrief.feedback.service.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    public ResponseEntity<ApiResponse<FeedbackSubmittedView>> submit(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody SubmitFeedbackRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                feedbackService.submit(principal.id(), request)));
    }
}
