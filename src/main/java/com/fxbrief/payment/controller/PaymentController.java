package com.fxbrief.payment.controller;

import com.fxbrief.common.dto.ApiResponse;
import com.fxbrief.payment.dto.ExchangeRateView;
import com.fxbrief.payment.service.ExchangeRateService;
import com.fxbrief.payment.service.PaymentWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final ExchangeRateService exchangeRateService;
    private final PaymentWebhookService paymentWebhookService;

    @GetMapping("/api/exchange-rate")
    public ResponseEntity<ApiResponse<ExchangeRateView>> getExchangeRate() {
        return ResponseEntity.ok(ApiResponse.success(exchangeRateService.getCurrentRate()));
    }

    /**
     * Midtrans HTTP notification endpoint. Unauthenticated server-to-server callback; the
     * payload's signature is verified inside the service. Returns 200 once the
     * notification is accepted (including duplicate and pending deliveries) so Midtrans
     * stops retrying; an invalid signature surfaces as 401 via the global handler.
     */
    @PostMapping("/payment/webhook")
    public ResponseEntity<ApiResponse<Void>> handleWebhook(@RequestBody String rawPayload) {
        paymentWebhookService.handleNotification(rawPayload);
        return ResponseEntity.ok(ApiResponse.<Void>success(null));
    }
}
