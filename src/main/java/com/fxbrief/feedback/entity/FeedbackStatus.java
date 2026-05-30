package com.fxbrief.feedback.entity;

/**
 * Lifecycle of a feedback submission. New submissions are {@code PENDING};
 * an admin reply transitions the row to {@code REPLIED}. The admin list
 * derives its {@code replied} boolean from this value.
 */
public enum FeedbackStatus {
    PENDING,
    REPLIED
}
