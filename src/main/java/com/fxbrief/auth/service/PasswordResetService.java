package com.fxbrief.auth.service;

import com.fxbrief.auth.dto.ForgotPasswordRequest;
import com.fxbrief.auth.dto.MessageResponse;
import com.fxbrief.auth.dto.ResetPasswordRequest;
import com.fxbrief.auth.entity.PasswordResetToken;
import com.fxbrief.auth.repository.PasswordResetTokenRepository;
import com.fxbrief.auth.security.AuthProperties;
import com.fxbrief.auth.security.FrontendProperties;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.notification.service.NotificationService;
import com.fxbrief.user.entity.User;
import com.fxbrief.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final String GENERIC_FORGOT_MESSAGE =
            "If an account exists for this email, a password reset link has been sent.";

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenGenerator tokenGenerator;
    private final AuthProperties authProperties;
    private final FrontendProperties frontendProperties;
    private final NotificationService notificationService;

    @Transactional
    public MessageResponse requestReset(ForgotPasswordRequest request) {
        String email = request.email() == null ? null : request.email().trim().toLowerCase();
        Optional<User> userOpt = userRepository.findByEmail(email);

        if (userOpt.isEmpty()) {
            return new MessageResponse(GENERIC_FORGOT_MESSAGE);
        }

        User user = userOpt.get();

        String rawToken = tokenGenerator.generatePasswordResetToken();
        String tokenHash = tokenGenerator.hashToken(rawToken);

        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenHash(tokenHash);
        token.setExpiresAt(Instant.now().plus(Duration.ofMinutes(authProperties.passwordResetTtlMinutes())));
        tokenRepository.save(token);

        String resetLink = buildResetLink(rawToken);
        log.info("Generated password reset for user id={} email={} resetLink={}",
                user.getId(), user.getEmail(), resetLink);

        notificationService.sendPasswordResetEmail(user.getEmail(), user.getName(), resetLink);

        return new MessageResponse(GENERIC_FORGOT_MESSAGE);
    }

    @Transactional
    public MessageResponse resetPassword(ResetPasswordRequest request) {
        String tokenHash = tokenGenerator.hashToken(request.token());

        PasswordResetToken token = tokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new DomainException(
                        ErrorCodes.INVALID_TOKEN,
                        HttpStatus.BAD_REQUEST,
                        "Reset token is invalid"));

        Instant now = Instant.now();

        if (token.isUsed()) {
            throw new DomainException(
                    ErrorCodes.TOKEN_ALREADY_USED,
                    HttpStatus.BAD_REQUEST,
                    "Reset token has already been used");
        }

        if (token.isExpired(now)) {
            throw new DomainException(
                    ErrorCodes.TOKEN_EXPIRED,
                    HttpStatus.BAD_REQUEST,
                    "Reset token has expired");
        }

        User user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        token.setUsedAt(now);
        tokenRepository.save(token);

        return new MessageResponse("Password has been reset. You can now log in with your new password.");
    }

    private String buildResetLink(String token) {
        String base = frontendProperties.baseUrl();
        if (base == null || base.isBlank()) {
            return token;
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/reset-password?token=" + token;
    }
}
