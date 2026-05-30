package com.fxbrief.content.controller;

import com.fxbrief.auth.security.AuthenticatedUser;
import com.fxbrief.common.dto.ApiResponse;
import com.fxbrief.content.dto.AdminArticleDetailView;
import com.fxbrief.content.dto.AdminArticleListView;
import com.fxbrief.content.dto.CreateArticleRequest;
import com.fxbrief.content.dto.UpdateArticleRequest;
import com.fxbrief.content.service.ArticleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin authoring surface for articles. Mounted under {@code /admin} so the
 * {@code /admin/**} matcher in {@code SecurityConfig} gates it; the class-level
 * {@code @PreAuthorize} mirrors {@code AdminController} for defence in depth.
 */
@RestController
@RequestMapping("/admin/articles")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminArticleController {

    private final ArticleService articleService;

    @GetMapping
    public ResponseEntity<ApiResponse<AdminArticleListView>> list(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "category", required = false) String category) {
        return ResponseEntity.ok(ApiResponse.success(
                articleService.listForAdmin(page, status, category)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AdminArticleDetailView>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(articleService.getForAdmin(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AdminArticleDetailView>> create(
            @Valid @RequestBody CreateArticleRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(ApiResponse.success(
                articleService.create(request, principal.id())));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<AdminArticleDetailView>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateArticleRequest request) {
        return ResponseEntity.ok(ApiResponse.success(articleService.update(id, request)));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<ApiResponse<AdminArticleDetailView>> publish(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(articleService.publish(id)));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<AdminArticleDetailView>> archive(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(articleService.archive(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        articleService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
