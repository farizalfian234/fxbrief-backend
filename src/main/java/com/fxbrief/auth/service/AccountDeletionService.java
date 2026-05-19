package com.fxbrief.auth.service;

import com.fxbrief.auth.dto.CancelDeletionResponse;
import com.fxbrief.auth.dto.RequestDeletionResponse;
import com.fxbrief.auth.security.AccountProperties;
import com.fxbrief.auth.security.AuthenticatedUser;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.user.entity.User;
import com.fxbrief.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    private final UserRepository userRepository;
    private final AccountProperties accountProperties;

    @Transactional
    public RequestDeletionResponse requestDeletion(AuthenticatedUser principal) {
        User user = loadUser(principal);

        if (user.getDeletionRequestedAt() == null) {
            user.setDeletionRequestedAt(Instant.now());
            userRepository.save(user);
        }

        Instant deletionDate = user.getDeletionRequestedAt()
                .plus(Duration.ofDays(accountProperties.deletionGraceDays()));

        log.info("Account deletion requested for user id={} email={} deletionDate={}",
                user.getId(), user.getEmail(), deletionDate);

        return new RequestDeletionResponse(
                user.getId(),
                user.getEmail(),
                user.getDeletionRequestedAt(),
                deletionDate,
                "Account deletion requested. Your account will be permanently deleted after the grace period unless cancelled.");
    }

    @Transactional
    public CancelDeletionResponse cancelDeletion(AuthenticatedUser principal) {
        User user = loadUser(principal);

        user.setDeletionRequestedAt(null);
        user.setActive(true);
        userRepository.save(user);

        return new CancelDeletionResponse(
                user.getId(),
                user.getEmail(),
                "Account deletion has been cancelled.");
    }

    private User loadUser(AuthenticatedUser principal) {
        return userRepository.findById(principal.id())
                .orElseThrow(() -> new DomainException(
                        ErrorCodes.NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "User not found"));
    }
}
