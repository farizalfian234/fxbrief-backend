package com.fxbrief.notification.dto;

import com.fxbrief.notification.entity.EmailEventType;

/**
 * A fully-resolved email ready for the provider: recipient, subject, both
 * content parts, and the event type for the audit log.
 */
public record EmailMessage(
        String recipient,
        String subject,
        String htmlBody,
        String textBody,
        EmailEventType eventType
) {}
