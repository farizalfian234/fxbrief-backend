package com.fxbrief.report.controller;

import com.fxbrief.auth.security.AuthenticatedUser;
import com.fxbrief.common.dto.ApiResponse;
import com.fxbrief.report.dto.GenerateReportRequest;
import com.fxbrief.report.dto.HistoryView;
import com.fxbrief.report.dto.ReportView;
import com.fxbrief.report.service.ReportGenerationService;
import com.fxbrief.report.service.ReportHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportGenerationService reportGenerationService;
    private final ReportHistoryService reportHistoryService;

    /**
     * Body is optional (Addition 3). When omitted or both fields null, the
     * user's persisted preference is used (or no preference if none set).
     * When both {@code preferenceType} and {@code preferenceValue} are
     * present, they override the persisted preference for this generation
     * only and the persisted row is left untouched.
     */
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<ReportView>> generate(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestBody(required = false) GenerateReportRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                reportGenerationService.generate(principal.id(), request)));
    }

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<ReportView>> getToday(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(ApiResponse.success(
                reportGenerationService.getTodayReport(principal.id()).orElse(null)));
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<HistoryView>> getHistory(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(name = "page", defaultValue = "1") int page) {
        return ResponseEntity.ok(ApiResponse.success(
                reportHistoryService.getHistory(principal.id(), page)));
    }

    @GetMapping("/history/{reportId}")
    public ResponseEntity<ApiResponse<ReportView>> getArchivedReport(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long reportId) {
        return ResponseEntity.ok(ApiResponse.success(
                reportHistoryService.getArchivedReport(principal.id(), reportId)));
    }
}
