package com.fxbrief.admin.service;

import com.fxbrief.admin.dto.AdminDashboardStatsView;
import com.fxbrief.admin.dto.AdminUsageRowView;
import com.fxbrief.admin.dto.AdminUsageView;
import com.fxbrief.admin.dto.AdminUserListView;
import com.fxbrief.admin.dto.AdminUserRowView;
import com.fxbrief.admin.repository.AdminQueryRepository;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin read surface. Owns the dashboard quick-stats projection, the
 * paginated user list, and the paginated usage overview. All methods are
 * read-only; mutations live in {@link AdminService}.
 */
@Service
@RequiredArgsConstructor
public class AdminQueryService {

    static final int PAGE_SIZE = 20;

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
