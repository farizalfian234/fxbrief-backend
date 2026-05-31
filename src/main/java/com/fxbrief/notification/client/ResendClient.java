package com.fxbrief.notification.client;

import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.notification.config.NotificationProperties;
import com.fxbrief.notification.dto.EmailMessage;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Thin client over Resend's transactional email API (the official
 * {@code com.resend:resend-java} SDK). Builds a multipart (text + HTML) message
 * and sends it through {@link Resend#emails()}. Any SDK {@link ResendException}
 * or unexpected failure is translated into a {@link DomainException} so the
 * Resilience4j aspect retries uniformly, matching the pattern of the Phase 3A
 * analysis clients.
 *
 * <p>The sender is composed from the configured from-name and from-email as
 * {@code "Name <email>"}; Resend requires the sending domain to be verified.
 *
 * <p>Protected by Resilience4j retry and circuit breaker policies named
 * {@code resend}.
 */
@Slf4j
@Component
public class ResendClient {

    public static final String RESILIENCE_NAME = "resend";

    private final NotificationProperties properties;
    private final Resend resend;

    public ResendClient(NotificationProperties properties) {
        this.properties = properties;
        this.resend = new Resend(properties.apiKey());
    }

    @Retry(name = RESILIENCE_NAME)
    @CircuitBreaker(name = RESILIENCE_NAME)
    public void send(EmailMessage message) {
        CreateEmailOptions options = CreateEmailOptions.builder()
                .from(buildFrom())
                .to(message.recipient())
                .subject(message.subject())
                .html(message.htmlBody())
                .text(message.textBody())
                .build();

        try {
            resend.emails().send(options);
        } catch (ResendException e) {
            throw sendFailure("Resend send failure: " + e.getMessage());
        } catch (Exception e) {
            throw sendFailure("Resend transport failure: " + e.getMessage());
        }
    }

    private String buildFrom() {
        String name = properties.fromName();
        String email = properties.fromEmail();
        if (name == null || name.isBlank()) {
            return email;
        }
        return name + " <" + email + ">";
    }

    private DomainException sendFailure(String message) {
        return new DomainException(
                ErrorCodes.EMAIL_SEND_FAILED,
                HttpStatus.SERVICE_UNAVAILABLE,
                message);
    }
}
