package com.fxbrief.notification.service;

import com.fxbrief.notification.client.ResendClient;
import com.fxbrief.notification.config.NotificationAsyncConfig;
import com.fxbrief.notification.dto.EmailMessage;
import com.fxbrief.notification.entity.EmailLogStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Performs the actual send on the dedicated email executor and records the
 * outcome via {@link EmailLogWriter}. Email is non-critical (PRD §10): every
 * failure is caught, logged at WARN, and persisted as a FAILED row — it never
 * propagates back to the triggering flow, which has already committed by the
 * time this runs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailDispatcher {

    private final ResendClient resendClient;
    private final EmailLogWriter emailLogWriter;

    @Async(NotificationAsyncConfig.EMAIL_EXECUTOR)
    public void dispatch(EmailMessage message) {
        Instant attemptedAt = Instant.now();
        try {
            resendClient.send(message);
            emailLogWriter.record(message, EmailLogStatus.SUCCESS, null, attemptedAt);
        } catch (Exception e) {
            log.warn("Email send failed: event={} recipient={} reason={}",
                    message.eventType(), message.recipient(), e.getMessage());
            emailLogWriter.record(message, EmailLogStatus.FAILED, truncate(e.getMessage()), attemptedAt);
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
