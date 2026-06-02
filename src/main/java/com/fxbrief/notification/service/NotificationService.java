package com.fxbrief.notification.service;

import com.fxbrief.notification.config.NotificationProperties;
import com.fxbrief.notification.dto.EmailMessage;
import com.fxbrief.notification.entity.EmailEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Entry point the rest of the application calls to send transactional email.
 * Each public method builds the resolved {@link EmailMessage} and hands it to
 * {@link #schedule}, which defers the actual (async) dispatch until after the
 * caller's transaction commits — so a rolled-back registration, verification,
 * deletion request, or feedback write never produces an email. When there is no
 * active transaction (e.g. a caller outside a transactional boundary) the
 * dispatch fires immediately.
 *
 * <p>The application base URL for links and the logo is the configured frontend
 * base URL (FRONTEND_BASE_URL); the email verification link is built to match
 * the path the auth flow already issues.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final EmailTemplates templates;
    private final EmailDispatcher dispatcher;
    private final NotificationProperties properties;
    private final AppUrlProvider appUrlProvider;

    public void sendVerificationEmail(String recipient, String name, String verificationUrl) {
        String appUrl = appUrlProvider.appUrl();
        schedule(new EmailMessage(
                recipient,
                templates.verificationSubject(),
                templates.verificationHtml(appUrl, name, verificationUrl),
                templates.verificationText(name, verificationUrl),
                EmailEventType.EMAIL_VERIFICATION));
    }

    public void sendWelcomeEmail(String recipient, String name) {
        String appUrl = appUrlProvider.appUrl();
        schedule(new EmailMessage(
                recipient,
                templates.welcomeSubject(name),
                templates.welcomeHtml(appUrl, name),
                templates.welcomeText(appUrl, name),
                EmailEventType.WELCOME));
    }

    public void sendReportsExhaustedEmail(String recipient, String name) {
        String appUrl = appUrlProvider.appUrl();
        schedule(new EmailMessage(
                recipient,
                templates.reportsExhaustedSubject(),
                templates.reportsExhaustedHtml(appUrl, name),
                templates.reportsExhaustedText(appUrl, name),
                EmailEventType.REPORTS_EXHAUSTED));
    }

    public void sendFeedbackThankYouEmail(String recipient, String name) {
        String appUrl = appUrlProvider.appUrl();
        schedule(new EmailMessage(
                recipient,
                templates.feedbackThankYouSubject(),
                templates.feedbackThankYouHtml(appUrl, name),
                templates.feedbackThankYouText(name),
                EmailEventType.FEEDBACK_THANK_YOU));
    }

    public void sendFeedbackAdminNotification(String name, String email, String feedbackContent, Instant submittedAt) {
        String appUrl = appUrlProvider.appUrl();
        String timestamp = TIMESTAMP_FORMAT.format(submittedAt);
        schedule(new EmailMessage(
                properties.adminEmail(),
                templates.feedbackAdminSubject(name),
                templates.feedbackAdminHtml(appUrl, name, email, feedbackContent, timestamp),
                templates.feedbackAdminText(name, email, feedbackContent, timestamp),
                EmailEventType.FEEDBACK_ADMIN_NOTIFICATION));
    }

    public void sendDeletionConfirmationEmail(String recipient, String name, Instant deletionDate) {
        String appUrl = appUrlProvider.appUrl();
        String date = DATE_FORMAT.format(deletionDate);
        schedule(new EmailMessage(
                recipient,
                templates.deletionSubject(),
                templates.deletionHtml(appUrl, name, date),
                templates.deletionText(name, date),
                EmailEventType.ACCOUNT_DELETION));
    }

    public void sendFeedbackReplyEmail(String recipient, String name, String replyContent) {
        String appUrl = appUrlProvider.appUrl();
        schedule(new EmailMessage(
                recipient,
                templates.feedbackReplySubject(),
                templates.feedbackReplyHtml(appUrl, name, replyContent),
                templates.feedbackReplyText(name, replyContent),
                EmailEventType.FEEDBACK_REPLY));
    }

    public void sendPasswordResetEmail(String recipient, String name, String resetUrl) {
        String appUrl = appUrlProvider.appUrl();
        schedule(new EmailMessage(
                recipient,
                templates.passwordResetSubject(),
                templates.passwordResetHtml(appUrl, name, resetUrl),
                templates.passwordResetText(name, resetUrl),
                EmailEventType.PASSWORD_RESET));
    }

    public void sendTopUpSuccessEmail(String recipient, String name, String planName,
                                      int reportsAdded, int previousCount, int newTotal) {
        String appUrl = appUrlProvider.appUrl();
        schedule(new EmailMessage(
                recipient,
                templates.topUpSuccessSubject(),
                templates.topUpSuccessHtml(appUrl, name, planName, reportsAdded, previousCount, newTotal),
                templates.topUpSuccessText(appUrl, name, planName, reportsAdded, previousCount, newTotal),
                EmailEventType.TOP_UP_SUCCESS));
    }

    private void schedule(EmailMessage message) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatcher.dispatch(message);
                }
            });
        } else {
            dispatcher.dispatch(message);
        }
    }
}
