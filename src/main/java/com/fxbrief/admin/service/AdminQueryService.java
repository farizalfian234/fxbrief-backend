package com.fxbrief.admin.service;

import com.fxbrief.admin.dto.ActiveInactiveView;
import com.fxbrief.admin.dto.AdminDashboardStatsView;
import com.fxbrief.admin.dto.AdminUsageRowView;
import com.fxbrief.admin.dto.AdminUsageView;
import com.fxbrief.admin.dto.AdminUserListView;
import com.fxbrief.admin.dto.AdminUserRowView;
import com.fxbrief.admin.dto.DailyReportVolumePoint;
import com.fxbrief.admin.dto.MonthlyCountPoint;
import com.fxbrief.admin.dto.MonthlyRevenuePoint;
import com.fxbrief.admin.repository.AdminQueryRepository;
import com.fxbrief.admin.repository.AdminQueryRepository.DailyVolumeRow;
import com.fxbrief.admin.repository.AdminQueryRepository.RevenueEventRow;
import com.fxbrief.admin.repository.AdminQueryRepository.UserSubscriptionRow;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.report.entity.UserReport;
import com.fxbrief.subscription.entity.PlanCode;
import com.fxbrief.subscription.entity.Subscription;
import com.fxbrief.subscription.service.ForexMarketClock;
import com.fxbrief.user.entity.SystemRole;
import com.fxbrief.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin read surface. Owns the dashboard quick-stats projection, the
 * paginated user list, the paginated usage overview, and (Phase 4B) the four
 * dashboard analytics series. All methods are read-only; mutations live in
 * {@link AdminService}.
 */
@Service
@RequiredArgsConstructor
public class AdminQueryService {

    static final int PAGE_SIZE = 20;

    /** Trailing window for the monthly charts (revenue, new subscribers). */
    static final int ANALYTICS_MONTHS = 12;
    /** Trailing window for the daily report-volume chart. */
    static final int DAILY_VOLUME_DAYS = 30;

    /**
     * Hours added to an event instant to shift the 22:00 UTC forex-day
     * boundary onto midnight, so the calendar date of the shifted instant is
     * the forex market date. Mirrors {@code ForexMarketClock}'s
     * {@code DAY_BOUNDARY_HOUR_UTC = 22} (24 - 22). Computed locally because
     * the audit table stores only {@code created_at} and {@code ForexMarketClock}
     * has no instant-to-forex-date overload; kept here as the single reference
     * used by the analytics bucketing.
     */
    private static final long FOREX_DAY_SHIFT_HOURS = 2;

    private final AdminQueryRepository adminQueryRepository;
    private final ForexMarketClock forexMarketClock;

    @Transactional(readOnly = true)
    public AdminDashboardStatsView getDashboardStats() {
        LocalDate today = forexMarketClock.currentForexMarketDate();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());

        long activeSubscribers = adminQueryRepository.countActiveSubscribers();
        long reportsThisMonth = adminQueryRepository.countReportsInForexDateRange(monthStart, monthEnd);
        long activeFreeUsers = adminQueryRepository.countActiveFreeUsers();
        long zeroRemaining = adminQueryRepository.countUsersAtZeroRemainingReports();

        return new AdminDashboardStatsView(
                activeSubscribers,
                reportsThisMonth,
                activeFreeUsers,
                zeroRemaining);
    }

    @Transactional(readOnly = true)
    public AdminUserListView listUsers(int page) {
        int requestedPage = Math.max(page, 1);
        int offset = (requestedPage - 1) * PAGE_SIZE;
        short adminRoleId = SystemRole.ADMIN.getId();

        long totalCount = adminQueryRepository.countNonAdminUsers(adminRoleId);
        int totalPages = (totalCount == 0) ? 0 : (int) Math.ceil((double) totalCount / PAGE_SIZE);

        List<UserSubscriptionRow> rows = adminQueryRepository
                .findNonAdminUserSubscriptionPage(adminRoleId, offset, PAGE_SIZE);

        List<Long> userIds = rows.stream().map(r -> r.user().getId()).toList();
        Map<Long, Instant> lastGeneratedByUserId =
                adminQueryRepository.findLastGeneratedAtByUserIds(userIds);

        List<AdminUserRowView> items = new ArrayList<>(rows.size());
        for (UserSubscriptionRow row : rows) {
            User user = row.user();
            Subscription subscription = row.subscription();
            items.add(new AdminUserRowView(
                    user.getId(),
                    user.getEmail(),
                    user.getName(),
                    subscription.getPlan().getName(),
                    subscription.getRemainingReports(),
                    user.isActive(),
                    user.isHasEverPaid(),
                    user.getDeletionRequestedAt(),
                    lastGeneratedByUserId.get(user.getId())));
        }

        return new AdminUserListView(items, totalCount, requestedPage, PAGE_SIZE, totalPages);
    }

    @Transactional(readOnly = true)
    public AdminUsageView listUsage(int page, LocalDate from, LocalDate to, Long userId) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new DomainException(
                    ErrorCodes.INVALID_DATE_RANGE,
                    HttpStatus.BAD_REQUEST,
                    "from must be on or before to");
        }

        int requestedPage = Math.max(page, 1);
        int offset = (requestedPage - 1) * PAGE_SIZE;

        long totalCount = adminQueryRepository.countUsageRows(from, to, userId);
        int totalPages = (totalCount == 0) ? 0 : (int) Math.ceil((double) totalCount / PAGE_SIZE);

        List<UserReport> rows = adminQueryRepository.findUsageRows(from, to, userId, offset, PAGE_SIZE);
        List<AdminUsageRowView> items = new ArrayList<>(rows.size());
        for (UserReport report : rows) {
            User user = report.getUser();
            items.add(new AdminUsageRowView(
                    report.getId(),
                    user.getId(),
                    user.getEmail(),
                    user.getName(),
                    report.getForexMarketDate(),
                    planCodeFromId(report.getPlanAtGeneration()),
                    report.isCountedAgainstLimit(),
                    report.getGeneratedAt()));
        }

        return new AdminUsageView(items, totalCount, requestedPage, PAGE_SIZE, totalPages);
    }

    /**
     * Revenue per forex month over the trailing {@link #ANALYTICS_MONTHS}
     * window. Each paid top-up event contributes the price of the plan it
     * credited (PRD §5.6: every top-up adds a plan's worth of reports at the
     * plan's price). Months with no revenue are present with a zero value so
     * the chart has a continuous axis.
     */
    @Transactional(readOnly = true)
    public List<MonthlyRevenuePoint> getMonthlyRevenue() {
        YearMonth currentMonth = currentForexMonth();
        YearMonth windowStart = currentMonth.minusMonths(ANALYTICS_MONTHS - 1L);
        Instant from = startInstantOf(windowStart);

        Map<YearMonth, BigDecimal> totals = new LinkedHashMap<>();
        for (RevenueEventRow event : adminQueryRepository.findRevenueEvents(from)) {
            YearMonth month = forexMonthOf(event.createdAt());
            if (month.isBefore(windowStart)) {
                continue;
            }
            totals.merge(month, event.price(), BigDecimal::add);
        }

        List<MonthlyRevenuePoint> points = new ArrayList<>(ANALYTICS_MONTHS);
        for (int i = 0; i < ANALYTICS_MONTHS; i++) {
            YearMonth month = windowStart.plusMonths(i);
            BigDecimal revenue = totals.getOrDefault(month, BigDecimal.ZERO);
            points.add(new MonthlyRevenuePoint(month.toString(), revenue));
        }
        return points;
    }

    /**
     * New (first-time) subscribers per forex month over the trailing
     * {@link #ANALYTICS_MONTHS} window. A user counts in the month of their
     * earliest paid top-up event. Months with no new subscribers are present
     * with a zero count.
     */
    @Transactional(readOnly = true)
    public List<MonthlyCountPoint> getNewSubscribersPerMonth() {
        YearMonth currentMonth = currentForexMonth();
        YearMonth windowStart = currentMonth.minusMonths(ANALYTICS_MONTHS - 1L);

        Map<YearMonth, Long> counts = new LinkedHashMap<>();
        for (Instant firstPaid : adminQueryRepository.findFirstPaidInstants()) {
            YearMonth month = forexMonthOf(firstPaid);
            if (month.isBefore(windowStart) || month.isAfter(currentMonth)) {
                continue;
            }
            counts.merge(month, 1L, Long::sum);
        }

        List<MonthlyCountPoint> points = new ArrayList<>(ANALYTICS_MONTHS);
        for (int i = 0; i < ANALYTICS_MONTHS; i++) {
            YearMonth month = windowStart.plusMonths(i);
            points.add(new MonthlyCountPoint(month.toString(), counts.getOrDefault(month, 0L)));
        }
        return points;
    }

    /**
     * Current active-vs-inactive split for non-admin users. The schema holds
     * only the present {@code is_active} value, so this is a point-in-time
     * snapshot rather than a historical series (see {@link ActiveInactiveView}).
     */
    @Transactional(readOnly = true)
    public ActiveInactiveView getActiveInactive() {
        short adminRoleId = SystemRole.ADMIN.getId();
        long active = adminQueryRepository.countActiveUsers(adminRoleId);
        long inactive = adminQueryRepository.countInactiveUsers(adminRoleId);
        return new ActiveInactiveView(active, inactive);
    }

    /**
     * Daily report-generation volume over the trailing
     * {@link #DAILY_VOLUME_DAYS} forex market days. Days with no reports are
     * present with a zero count so the chart axis is continuous.
     */
    @Transactional(readOnly = true)
    public List<DailyReportVolumePoint> getDailyReportVolume() {
        LocalDate today = forexMarketClock.currentForexMarketDate();
        LocalDate windowStart = today.minusDays(DAILY_VOLUME_DAYS - 1L);

        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        for (DailyVolumeRow row : adminQueryRepository.findDailyReportVolume(windowStart)) {
            counts.put(row.forexMarketDate(), row.count());
        }

        List<DailyReportVolumePoint> points = new ArrayList<>(DAILY_VOLUME_DAYS);
        for (int i = 0; i < DAILY_VOLUME_DAYS; i++) {
            LocalDate day = windowStart.plusDays(i);
            points.add(new DailyReportVolumePoint(day, counts.getOrDefault(day, 0L)));
        }
        return points;
    }

    private YearMonth currentForexMonth() {
        return YearMonth.from(forexMarketClock.currentForexMarketDate());
    }

    private static YearMonth forexMonthOf(Instant instant) {
        return YearMonth.from(
                instant.plus(Duration.ofHours(FOREX_DAY_SHIFT_HOURS)).atZone(ZoneOffset.UTC));
    }

    private static Instant startInstantOf(YearMonth month) {
        // First instant whose forex month is `month`: 22:00 UTC on the last day
        // of the previous calendar month. Shifting back by the forex-day offset
        // from the month's first calendar midnight yields that boundary.
        return month.atDay(1).atStartOfDay(ZoneOffset.UTC)
                .minusHours(FOREX_DAY_SHIFT_HOURS)
                .toInstant();
    }

    private static String planCodeFromId(short planId) {
        if (planId == PlanCode.FREE.getId()) {
            return PlanCode.FREE.getCode();
        }
        if (planId == PlanCode.BASIC.getId()) {
            return PlanCode.BASIC.getCode();
        }
        if (planId == PlanCode.PREMIUM.getId()) {
            return PlanCode.PREMIUM.getCode();
        }
        throw new IllegalStateException("Unknown plan id: " + planId);
    }
}
