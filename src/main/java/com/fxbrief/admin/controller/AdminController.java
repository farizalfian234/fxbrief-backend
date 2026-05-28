package com.fxbrief.admin.controller;

import com.fxbrief.admin.dto.AdminAccountStatusView;
import com.fxbrief.admin.dto.AdminDashboardStatsView;
import com.fxbrief.admin.dto.AdminTopUpRequest;
import com.fxbrief.admin.dto.AdminTopUpView;
import com.fxbrief.admin.dto.AdminUsageView;
import com.fxbrief.admin.dto.AdminUserListView;
import com.fxbrief.admin.service.AdminQueryService;
import com.fxbrief.admin.service.AdminService;
import com.fxbrief.auth.security.AuthenticatedUser;
import com.fxbrief.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final AdminQueryService adminQueryService;
    private final AdminService adminService;

    @GetMapping("/dashboard/stats")
    public ResponseEntity<ApiResponse<AdminDashboardStatsView>> getDashboardStats() {
        return ResponseEntity.ok(ApiResponse.success(adminQueryService.getDashboardStats()));
    }

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<AdminUserListView>> listUsers(
            @RequestParam(name = "page", defaultValue = "1") int page) {
        return ResponseEntity.ok(ApiResponse.success(adminQueryService.listUsers(page)));
    }

    @PostMapping("/users/{userId}/top-up")
    public ResponseEntity<ApiResponse<AdminTopUpView>> manualTopUp(
            @PathVariable Long userId,
            @Valid @RequestBody AdminTopUpRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(ApiResponse.success(
                adminService.manualTopUp(userId, request, principal)));
    }

    @PostMapping("/users/{userId}/activate")
    public ResponseEntity<ApiResponse<AdminAccountStatusView>> activateUser(
            @PathVariable Long userId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(ApiResponse.success(
                adminService.activate(userId, principal)));
    }

    @PostMapping("/users/{userId}/deactivate")
    public ResponseEntity<ApiResponse<AdminAccountStatusView>> deactivateUser(
            @PathVariable Long userId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(ApiResponse.success(
                adminService.deactivate(userId, principal)));
    }

    @GetMapping("/usage")
    public ResponseEntity<ApiResponse<AdminUsageView>> listUsage(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "userId", required = false) Long userId) {
        return ResponseEntity.ok(ApiResponse.success(
                adminQueryService.listUsage(page, from, to, userId)));
    }
}
