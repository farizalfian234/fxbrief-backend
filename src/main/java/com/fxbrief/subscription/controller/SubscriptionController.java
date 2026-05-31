package com.fxbrief.subscription.controller;

import com.fxbrief.auth.security.AuthenticatedUser;
import com.fxbrief.common.dto.ApiResponse;
import com.fxbrief.payment.dto.SnapTransactionView;
import com.fxbrief.payment.service.PaymentService;
import com.fxbrief.subscription.dto.RemainingReportsView;
import com.fxbrief.subscription.dto.SubscriptionView;
import com.fxbrief.subscription.dto.TopUpRequest;
import com.fxbrief.subscription.service.SubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/subscription")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final PaymentService paymentService;

    @GetMapping
    public ResponseEntity<ApiResponse<SubscriptionView>> getSubscription(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(ApiResponse.success(
                subscriptionService.getSubscriptionFor(principal.id())));
    }

    @GetMapping("/remaining")
    public ResponseEntity<ApiResponse<RemainingReportsView>> getRemainingReports(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(ApiResponse.success(
                subscriptionService.getRemainingReportsFor(principal.id())));
    }

    @PostMapping("/top-up")
    public ResponseEntity<ApiResponse<SnapTransactionView>> initiateTopUp(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody TopUpRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                paymentService.initiateTopUp(principal.id(), request)));
    }
}
