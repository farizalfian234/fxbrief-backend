package com.fxbrief.auth.service;

import com.fxbrief.auth.dto.RegisterRequest;
import com.fxbrief.auth.dto.RegisterResponse;
import com.fxbrief.auth.entity.EmailVerificationToken;
import com.fxbrief.auth.repository.EmailVerificationTokenRepository;
import com.fxbrief.auth.security.AuthProperties;
import com.fxbrief.auth.security.FrontendProperties;
import com.fxbrief.auth.validator.DisposableEmailDomainRegistry;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.subscription.service.SubscriptionService;
import com.fxbrief.user.entity.Role;
import com.fxbrief.user.entity.SystemRole;
import com.fxbrief.user.entity.User;
import com.fxbrief.user.repository.RoleRepository;
import com.fxbrief.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenGenerator tokenGenerator;
    private final DisposableEmailDomainRegistry disposableEmailRegistry;
    private final AuthProperties authProperties;
    private final FrontendProperties frontendProperties;
    private final SubscriptionService subscriptionService;

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());

        if (disposableEmailRegistry.isDisposable(email)) {
            throw new DomainException(
                    ErrorCodes.EMAIL_DOMAIN_NOT_ALLOWED,
                    HttpStatus.BAD_REQUEST,
                    "Email domain is not allowed");
        }

        if (userRepository.existsByEmail(email)) {
            throw new DomainException(
                    ErrorCodes.EMAIL_ALREADY_REGISTERED,
                    HttpStatus.CONFLICT,
                    "An account with this email already exists");
        }

        Role userRole = roleRepository.getReferenceById(SystemRole.USER.getId());

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setName(request.name().trim());
        user.setRole(userRole);
        user.setActive(false);
        user.setHasEverPaid(false);
        userRepository.save(user);

        subscriptionService.provisionFreePlan(user);

        EmailVerificationToken token = new EmailVerificationToken();
        token.setUser(user);
        token.setToken(tokenGenerator.generateVerificationToken());
        token.setExpiresAt(Instant.now().plus(Duration.ofHours(authProperties.emailVerificationTtlHours())));
        tokenRepository.save(token);

        log.info("Registered user id={} email={} verificationLink={}",
                user.getId(), user.getEmail(), buildVerificationLink(token.getToken()));

        return new RegisterResponse(
                user.getId(),
                user.getEmail(),
                "Registration successful. Please verify your email to activate your account.");
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private String buildVerificationLink(String token) {
        String base = frontendProperties.baseUrl();
        if (base == null || base.isBlank()) {
            return token;
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/verify-email?token=" + token;
    }
}
