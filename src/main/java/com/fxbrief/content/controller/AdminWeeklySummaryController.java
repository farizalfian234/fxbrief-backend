package com.fxbrief.content.controller;

import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.dto.ApiResponse;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.content.dto.UpdateWeeklySummaryRequest;
import com.fxbrief.content.dto.WeeklySummaryDetailView;
import com.fxbrief.content.dto.WeeklySummaryListView;
import com.fxbrief.content.entity.WeeklySummaryStatus;
import com.fxbrief.content.service.WeeklySummaryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin review surface for weekly market summaries. Mounted under {@code /admin}
 * so the {@code /admin/**} matcher in {@code SecurityConfig} gates it; the
 * class-level {@code @PreAuthorize} mirrors {@code AdminController} for defence
 * in depth.
 */
@RestController
@RequestMapping("/admin/weekly-summaries")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminWeeklySummaryController {

    private final WeeklySummaryService weeklySummaryService;

    @GetMapping
    public ResponseEntity<ApiResponse<WeeklySummaryListView>> list(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "status", required = false) String status) {
        WeeklySummaryStatus filter = parseStatusFilter(status);
        return ResponseEntity.ok(ApiResponse.success(
                weeklySummaryService.listForAdmin(page, filter)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<WeeklySummaryDetailView>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(weeklySummaryService.getForAdmin(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<WeeklySummaryDetailView>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateWeeklySummaryRequest request) {
        return ResponseEntity.ok(ApiResponse.success(weeklySummaryService.update(id, request)));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<ApiResponse<WeeklySummaryDetailView>> publish(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(weeklySummaryService.publish(id)));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<WeeklySummaryDetailView>> archive(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(weeklySummaryService.archive(id)));
    }

    private WeeklySummaryStatus parseStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return WeeklySummaryStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DomainException(
                    ErrorCodes.INVALID_WEEKLY_SUMMARY_STATUS,
                    HttpStatus.BAD_REQUEST,
                    "Status must be one of DRAFT, PUBLISHED, ARCHIVED");
        }
    }
}
