package com.fxbrief.report.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin wrapper over Postgres transaction-scoped advisory locks. The lock is
 * released automatically at transaction commit or rollback — no explicit
 * unlock call is required, and there is no leak risk if the surrounding
 * transaction aborts.
 *
 * Used by report generation to serialise concurrent generate requests from
 * the same user (a double tap on the Generate button would otherwise race
 * past the per-day uniqueness check and waste a Claude call on the loser).
 *
 * The {@code MANDATORY} propagation is deliberate: an advisory lock taken
 * outside a transaction would release immediately and accomplish nothing.
 */
@Component
@RequiredArgsConstructor
public class AdvisoryLockService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockUser(long userId) {
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(?)", rs -> {}, userId);
    }
}
