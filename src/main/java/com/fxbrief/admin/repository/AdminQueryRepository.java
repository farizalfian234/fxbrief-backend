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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-scoped read queries. Pulls together {@code users}, {@code subscriptions},
 * {@code subscription_plans}, {@code user_reports}, and (Phase 4B)
 * {@code subscription_audit_logs} to populate the admin dashboard, user list,
 * usage overview, and the dashboard analytics charts.
 *
 * Native repository rather than Spring Data because the queries cut across
 * feature modules; placing them in any single feature repository would leak
 * cross-module concerns. Read-only — admin mutations go through
 * {@link com.fxbrief.admin.service.AdminService}.
 */
@Repository
public class AdminQueryRepository {

    /**
     * Audit actions that represent a paid top-up and therefore count toward
     * revenue. {@code TOP_UP} is the Midtrans payment introduced in Phase 5B;
     * the two admin actions are the manual fallback used while Midtrans
     * onboarding is pending. All three add a plan's worth of report credit at
     * the plan's price.
     */
    private static final List<String> REVENUE_ACTIONS =
            List.of("TOP_UP", "ADMIN_TOP_UP", "ADMIN_PLAN_CHANGE");

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

    // ---------------------------------------------------------------------
    // Phase 4B — dashboard analytics
    // ---------------------------------------------------------------------

    /**
     * Returns one tuple per revenue-bearing audit event on or after
     * {@code fromInclusive}: {@code (created_at, new_plan_price)}. Forex-month
     * bucketing and summation happen in the service so the 22:00 UTC forex-day
     * boundary stays in one place (the audit table has no forex_market_date
     * column). Ordered oldest-first.
     */
    public List<RevenueEventRow> findRevenueEvents(Instant fromInclusive) {
        List<Object[]> rows = em.createQuery(
                "SELECT a.createdAt, a.newPlan.price FROM SubscriptionAuditLog a " +
                "WHERE a.action IN :actions AND a.newPlan IS NOT NULL " +
                "AND a.createdAt >= :from " +
                "ORDER BY a.createdAt ASC", Object[].class)
                .setParameter("actions", REVENUE_ACTIONS)
                .setParameter("from", fromInclusive)
                .getResultList();
        List<RevenueEventRow> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            out.add(new RevenueEventRow((Instant) row[0], (BigDecimal) row[1]));
        }
        return out;
    }

    /**
     * Returns, per user, the timestamp of that user's earliest revenue-bearing
     * audit event — i.e. when they first became a paying subscriber. Used to
     * bucket first-time subscribers by forex month. Users who have never paid
     * are absent.
     */
    public List<Instant> findFirstPaidInstants() {
        return em.createQuery(
                "SELECT MIN(a.createdAt) FROM SubscriptionAuditLog a " +
                "WHERE a.action IN :actions " +
                "GROUP BY a.user.id", Instant.class)
                .setParameter("actions", REVENUE_ACTIONS)
                .getResultList();
    }

    /**
     * Daily report-generation volume by forex market date on or after
     * {@code fromInclusive}, ordered oldest day first.
     */
    public List<DailyVolumeRow> findDailyReportVolume(LocalDate fromInclusive) {
        List<Object[]> rows = em.createQuery(
                "SELECT r.forexMarketDate, COUNT(r) FROM UserReport r " +
                "WHERE r.forexMarketDate >= :from " +
                "GROUP BY r.forexMarketDate ORDER BY r.forexMarketDate ASC", Object[].class)
                .setParameter("from", fromInclusive)
                .getResultList();
        List<DailyVolumeRow> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            out.add(new DailyVolumeRow((LocalDate) row[0], ((Number) row[1]).longValue()));
        }
        return out;
    }

    public long countActiveUsers(short adminRoleId) {
        return em.createQuery(
                "SELECT COUNT(u) FROM User u WHERE u.role.id <> :adminRoleId AND u.active = true",
                Long.class)
                .setParameter("adminRoleId", adminRoleId)
                .getSingleResult();
    }

    public long countInactiveUsers(short adminRoleId) {
        return em.createQuery(
                "SELECT COUNT(u) FROM User u WHERE u.role.id <> :adminRoleId AND u.active = false",
                Long.class)
                .setParameter("adminRoleId", adminRoleId)
                .getSingleResult();
    }

    /**
     * Projection tuple for the user list page.
     */
    public record UserSubscriptionRow(User user, Subscription subscription) {}

    /**
     * Projection tuple for a single revenue event: the event timestamp and the
     * price of the plan credited.
     */
    public record RevenueEventRow(Instant createdAt, BigDecimal price) {}

    /**
     * Projection tuple for daily report volume.
     */
    public record DailyVolumeRow(LocalDate forexMarketDate, long count) {}
}
