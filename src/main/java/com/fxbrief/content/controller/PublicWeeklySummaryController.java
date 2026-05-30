package com.fxbrief.content.controller;

import com.fxbrief.common.dto.ApiResponse;
import com.fxbrief.content.dto.PublicWeeklySummaryListView;
import com.fxbrief.content.dto.PublicWeeklySummaryView;
import com.fxbrief.content.service.WeeklySummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated read surface for published weekly recaps. Routes
 * under {@code /public/**} are on the {@code SecurityConfig} allowlist.
 */
@RestController
@RequestMapping("/public/weekly-summaries")
@RequiredArgsConstructor
public class PublicWeeklySummaryController {

    private final WeeklySummaryService weeklySummaryService;

    @GetMapping
    public ResponseEntity<ApiResponse<PublicWeeklySummaryListView>> list(
            @RequestParam(name = "page", defaultValue = "1") int page) {
        return ResponseEntity.ok(ApiResponse.success(weeklySummaryService.listPublished(page)));
    }

    @GetMapping("/{slug}")
    public ResponseEntity<ApiResponse<PublicWeeklySummaryView>> get(@PathVariable String slug) {
        return ResponseEntity.ok(ApiResponse.success(weeklySummaryService.getPublishedBySlug(slug)));
    }
}
