package com.fxbrief.notification.service;

import com.fxbrief.notification.dto.EmailMessage;
import com.fxbrief.notification.entity.EmailLog;
import com.fxbrief.notification.entity.EmailLogStatus;
import com.fxbrief.notification.repository.EmailLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Writes one {@code email_logs} row per send attempt in its own short
 * transaction. Lives in a dedicated bean so the {@code @Transactional}
 * annotation is honoured through Spring's AOP proxy when called from
 * {@link EmailDispatcher} — the same self-invocation guard the analysis
 * module's cache writers use (DECISIONS D-039). A persistence failure here is
 * caught and logged; it never propagates, because email is non-critical.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailLogWriter {

    private final EmailLogRepository emailLogRepository;

    @Transactional
    public void record(EmailMessage message, EmailLogStatus status, String error, Instant sentAt) {
        try {
            EmailLog row = new EmailLog();
            row.setRecipient(message.recipient());
            row.setSubject(message.subject());
            row.setEventType(message.eventType());
            row.setStatus(status);
            row.setErrorMessage(error);
            row.setSentAt(sentAt);
            emailLogRepository.save(row);
        } catch (Exception e) {
            log.warn("Failed to persist email_logs row: event={} reason={}",
                    message.eventType(), e.getMessage());
        }
    }
}
