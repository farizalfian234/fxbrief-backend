package com.fxbrief.payment.service;

import com.fxbrief.payment.config.MidtransProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Verifies the Midtrans notification signature. Midtrans signs each notification as
 * {@code SHA-512(order_id + status_code + gross_amount + server_key)} and sends the hex
 * digest as {@code signature_key}. The {@code gross_amount} and {@code status_code} must
 * be used exactly as received in the payload. The comparison is constant-time.
 */
@Component
@RequiredArgsConstructor
public class MidtransSignatureVerifier {

    private final MidtransProperties properties;

    public boolean isValid(String orderId, String statusCode, String grossAmount,
                           String providedSignature) {
        if (orderId == null || statusCode == null || grossAmount == null
                || providedSignature == null) {
            return false;
        }
        String expected = sha512(orderId + statusCode + grossAmount + properties.serverKey());
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = providedSignature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }

    private String sha512(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 not available", e);
        }
    }
}
