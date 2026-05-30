package com.fxbrief.content.controller;

import com.fxbrief.common.dto.ApiResponse;
import com.fxbrief.content.dto.SitemapEntryView;
import com.fxbrief.content.service.SitemapService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public sitemap registry endpoint. The Phase 6A SSR build reads this to
 * generate sitemap.xml. Unauthenticated — on the {@code /public/**} allowlist.
 */
@RestController
@RequestMapping("/public/sitemap-entries")
@RequiredArgsConstructor
public class PublicSitemapController {

    private final SitemapService sitemapService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<SitemapEntryView>>> list() {
        return ResponseEntity.ok(ApiResponse.success(sitemapService.listAll()));
    }
}
