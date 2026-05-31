package com.fxbrief.notification.entity;

/**
 * Outcome of a single email send attempt, persisted as the enum name in
 * {@code email_logs.status}.
 */
public enum EmailLogStatus {
    SUCCESS,
    FAILED
}
