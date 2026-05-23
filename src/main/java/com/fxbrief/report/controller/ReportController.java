package com.fxbrief.report.controller;

import com.fxbrief.auth.security.AuthenticatedUser;
import com.fxbrief.common.dto.ApiResponse;
import com.fxbrief.report.dto.ReportView;
import com.fxbrief.report.service.ReportGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportGenerationService reportGenerationService;

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<ReportView>> generate(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(ApiResponse.success(
                reportGenerationService.generate(principal.id())));
    }

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<ReportView>> getToday(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(ApiResponse.success(
                reportGenerationService.getTodayReport(principal.id()).orElse(null)));
    }
}
