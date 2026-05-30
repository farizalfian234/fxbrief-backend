package com.fxbrief.report.controller;

import com.fxbrief.auth.security.AuthenticatedUser;
import com.fxbrief.common.dto.ApiResponse;
import com.fxbrief.report.dto.PreferenceOptionsView;
import com.fxbrief.report.dto.UpdatePreferenceRequest;
import com.fxbrief.report.dto.UserPreferenceView;
import com.fxbrief.report.service.UserPreferenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/preferences")
@RequiredArgsConstructor
public class UserPreferenceController {

    private final UserPreferenceService userPreferenceService;

    @GetMapping
    public ResponseEntity<ApiResponse<UserPreferenceView>> getPreference(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(ApiResponse.success(
                userPreferenceService.getPreference(principal.id())));
    }

    @GetMapping("/options")
    public ResponseEntity<ApiResponse<PreferenceOptionsView>> getOptions() {
        return ResponseEntity.ok(ApiResponse.success(userPreferenceService.getOptions()));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<UserPreferenceView>> setPreference(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdatePreferenceRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                userPreferenceService.setPreference(principal.id(), request)));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> clearPreference(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        userPreferenceService.clearPreference(principal.id());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
