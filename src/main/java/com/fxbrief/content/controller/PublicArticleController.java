package com.fxbrief.content.controller;

import com.fxbrief.common.dto.ApiResponse;
import com.fxbrief.content.dto.PublicArticleListView;
import com.fxbrief.content.dto.PublicArticleView;
import com.fxbrief.content.service.ArticleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public, unauthenticated read surface for published articles. Routes under
 * {@code /public/**} are on the {@code SecurityConfig} allowlist.
 */
@RestController
@RequestMapping("/public/articles")
@RequiredArgsConstructor
public class PublicArticleController {

    private final ArticleService articleService;

    @GetMapping
    public ResponseEntity<ApiResponse<PublicArticleListView>> list(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "tag", required = false) String tag) {
        return ResponseEntity.ok(ApiResponse.success(
                articleService.listPublished(page, category, tag)));
    }

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<String>>> categories() {
        return ResponseEntity.ok(ApiResponse.success(articleService.listPublishedCategories()));
    }

    @GetMapping("/{slug}")
    public ResponseEntity<ApiResponse<PublicArticleView>> get(@PathVariable String slug) {
        return ResponseEntity.ok(ApiResponse.success(articleService.getPublishedBySlug(slug)));
    }
}
