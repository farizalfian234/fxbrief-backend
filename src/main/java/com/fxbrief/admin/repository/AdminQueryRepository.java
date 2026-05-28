package com.fxbrief.admin.repository;

import com.fxbrief.report.entity.UserReport;
import com.fxbrief.subscription.entity.PlanCode;
import com.fxbrief.subscription.entity.Subscription;
import com.fxbrief.user.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-scoped read queries. Pulls together {@code users}, {@code subscriptions},
 * {@code subscription_plans}, and {@code user_reports} to populate the admin
 * dashboard, user list, and usage overview pages.
 *
 * Native repository rather than Spring Data because the queries cut across
 * feature modules; placing them in any single feature repository would leak
 * cross-module concerns. Read-only — admin mutations go through
 * {@link com.fxbrief.admin.service.AdminService}.
 */
@Repository
public class AdminQueryRepository {

    private final EntityManager em;

    @Autowired
    public AdminQueryRepository(EntityManager em) {
        this.em = em;
    }

    public long countActiveSubscribers() {
        return em.createQuery(
                "SELECT COUNT(s) FROM Subscription s " +
                "WHERE s.plan.id IN (:basicId, :premiumId) AND s.user.active = true", Long.class)
                .setParameter("basicId", PlanCode.BASIC.getId())
                .setParameter("premiumId", PlanCode.PREMIUM.getId())
                .getSingleResult();
    }

    public long countReportsInForexDateRange(LocalDate fromInclusive, LocalDate toInclusive) {
        return em.createQuery(
                "SELECT COUNT(r) FROM UserReport r " +
                "WHERE r.forexMarketDate >= :from AND r.forexMarketDate <= :to", Long.class)
                .setParameter("from", fromInclusive)
                .setParameter("to", toInclusive)
                .getSingleResult();
    }

    public long countActiveFreeUsers() {
        return em.createQuery(
                "SELECT COUNT(s) FROM Subscription s " +
                "WHERE s.plan.id = :freeId AND s.user.active = true", Long.class)
                .setParameter("freeId", PlanCode.FREE.getId())
                .getSingleResult();
    }

    public long countUsersAtZeroRemainingReports() {
        return em.createQuery(
                "SELECT COUNT(s) FROM Subscription s WHERE s.remainingReports = 0", Long.class)
                .getSingleResult();
    }

    public long countNonAdminUsers(short adminRoleId) {
        return em.createQuery(
                "SELECT COUNT(u) FROM User u WHERE u.role.id <> :adminRoleId", Long.class)
                .setParameter("adminRoleId", adminRoleId)
                .getSingleResult();
    }

    /**
     * Returns a page of non-admin users joined to their subscriptions, ordered
     * by user id ascending. Each tuple is {@code (User, Subscription)}; the
     * subscription is required because every user is provisioned with a Free
     * subscription at registration time (Phase 2A). Admin users are excluded
     * from the list so one admin cannot accidentally lock another out from
     * the panel.
     */
    public List<UserSubscriptionRow> findNonAdminUserSubscriptionPage(
            short adminRoleId, int offset, int limit) {
        List<Object[]> rows = em.createQuery(
                "SELECT u, s FROM User u, Subscription s " +
                "WHERE s.user.id = u.id AND u.role.id <> :adminRoleId " +
                "ORDER BY u.id ASC", Object[].class)
                .setParameter("adminRoleId", adminRoleId)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
        List<UserSubscriptionRow> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            out.add(new UserSubscriptionRow((User) row[0], (Subscription) row[1]));
        }
        return out;
    }

    /**
     * Returns the most recent {@code generated_at} timestamp per user for the
     * supplied user ids, in a single query. Users that have never generated a
     * report are absent from the map.
     */
    public Map<Long, Instant> findLastGeneratedAtByUserIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = em.createQuery(
                "SELECT r.user.id, MAX(r.generatedAt) FROM UserReport r " +
                "WHERE r.user.id IN :userIds GROUP BY r.user.id", Object[].class)
                .setParameter("userIds", userIds)
                .getResultList();
        Map<Long, Instant> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put(((Number) row[0]).longValue(), (Instant) row[1]);
        }
        return map;
    }

    /**
     * Paginated usage rows with optional date-range and user-id filters.
     * Ordered newest forex market day first, then newest generation timestamp
     * within the same day. The query joins {@code user_reports} to {@code users}
     * eagerly so the response projection can render name + email without an
     * N+1 lookup.
     */
    public List<UserReport> findUsageRows(
            LocalDate fromInclusive,
            LocalDate toInclusive,
            Long userIdFilter,
            int offset,
            int limit) {
        StringBuilder jpql = new StringBuilder("SELECT r FROM UserReport r JOIN FETCH r.user u");
        appendUsageFilters(jpql, fromInclusive, toInclusive, userIdFilter);
        jpql.append(" ORDER BY r.forexMarketDate DESC, r.generatedAt DESC, r.id DESC");

        TypedQuery<UserReport> query = em.createQuery(jpql.toString(), UserReport.class);
        bindUsageFilters(query, fromInclusive, toInclusive, userIdFilter);
        query.setFirstResult(offset);
        query.setMaxResults(limit);
        return query.getResultList();
    }

    public long countUsageRows(LocalDate fromInclusive, LocalDate toInclusive, Long userIdFilter) {
        StringBuilder jpql = new StringBuilder("SELECT COUNT(r) FROM UserReport r");
        appendUsageFilters(jpql, fromInclusive, toInclusive, userIdFilter);

        TypedQuery<Long> query = em.createQuery(jpql.toString(), Long.class);
        bindUsageFilters(query, fromInclusive, toInclusive, userIdFilter);
        return query.getSingleResult();
    }

    private static void appendUsageFilters(
            StringBuilder jpql,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            Long userIdFilter) {
        boolean first = true;
        if (fromInclusive != null) {
            jpql.append(first ? " WHERE" : " AND").append(" r.forexMarketDate >= :from");
            first = false;
        }
        if (toInclusive != null) {
            jpql.append(first ? " WHERE" : " AND").append(" r.forexMarketDate <= :to");
            first = false;
        }
        if (userIdFilter != null) {
            jpql.append(first ? " WHERE" : " AND").append(" r.user.id = :userId");
        }
    }

    private static void bindUsageFilters(
            Query query,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            Long userIdFilter) {
        if (fromInclusive != null) {
            query.setParameter("from", fromInclusive);
        }
        if (toInclusive != null) {
            query.setParameter("to", toInclusive);
        }
        if (userIdFilter != null) {
            query.setParameter("userId", userIdFilter);
        }
    }

    /**
     * Projection tuple for the user list page.
     */
    public record UserSubscriptionRow(User user, Subscription subscription) {}
}
