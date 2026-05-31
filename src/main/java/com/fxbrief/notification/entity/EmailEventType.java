package com.fxbrief.notification.entity;

/**
 * The transactional email events FX–Brief sends (PRD §10). Persisted as the
 * enum name in {@code email_logs.event_type}.
 */
public enum EmailEventType {
    EMAIL_VERIFICATION,
    WELCOME,
    REPORTS_EXHAUSTED,
    FEEDBACK_THANK_YOU,
    FEEDBACK_ADMIN_NOTIFICATION,
    ACCOUNT_DELETION,
    FEEDBACK_REPLY
}
