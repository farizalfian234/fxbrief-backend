package com.fxbrief.auth.service;

import com.fxbrief.auth.security.AccountProperties;
import com.fxbrief.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountDeletionScheduler {

    private final UserRepository userRepository;
    private final AccountProperties accountProperties;

    @Scheduled(cron = "${fxbrief.scheduler.hard-delete-cron}", zone = "UTC")
    @Transactional
    public void hardDeleteExpiredAccounts() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(accountProperties.deletionGraceDays()));
        int deleted = userRepository.hardDeleteRequestedBefore(cutoff);
        if (deleted > 0) {
            log.info("Hard-deleted {} account(s) past deletion grace period (cutoff={})", deleted, cutoff);
        } else {
            log.debug("No accounts past deletion grace period (cutoff={})", cutoff);
        }
    }
}
