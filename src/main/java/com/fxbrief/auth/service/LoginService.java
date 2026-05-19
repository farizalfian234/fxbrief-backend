package com.fxbrief.auth.service;

import com.fxbrief.auth.dto.LoginRequest;
import com.fxbrief.auth.dto.LoginResponse;
import com.fxbrief.auth.security.AccountProperties;
import com.fxbrief.auth.security.JwtService;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.user.entity.User;
import com.fxbrief.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class LoginService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AccountProperties accountProperties;

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String email = request.email() == null ? null : request.email().trim().toLowerCase();

        User user = userRepository.findByEmail(email)
                .orElseThrow(this::invalidCredentials);

        if (!user.hasPassword() || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }

        if (!user.isActive()) {
            if (user.getDeletionRequestedAt() == null) {
                throw new DomainException(
                        ErrorCodes.ACCOUNT_NOT_VERIFIED,
                        HttpStatus.FORBIDDEN,
                        "Please verify your email before logging in");
            }
            throw new DomainException(
                    ErrorCodes.ACCOUNT_INACTIVE,
                    HttpStatus.FORBIDDEN,
                    "Account is inactive");
        }

        String roleName = user.getRole().getName();
        JwtService.IssuedToken issued = jwtService.issue(user.getId(), user.getEmail(), roleName);

        boolean deletionPending = user.getDeletionRequestedAt() != null;
        Instant deletionDate = deletionPending
                ? user.getDeletionRequestedAt().plus(Duration.ofDays(accountProperties.deletionGraceDays()))
                : null;

        return new LoginResponse(
                issued.token(),
                "Bearer",
                issued.expiresAt(),
                user.getId(),
                user.getEmail(),
                user.getName(),
                roleName,
                deletionPending,
                deletionDate);
    }

    private DomainException invalidCredentials() {
        return new DomainException(
                ErrorCodes.INVALID_CREDENTIALS,
                HttpStatus.UNAUTHORIZED,
                "Invalid email or password");
    }
}
