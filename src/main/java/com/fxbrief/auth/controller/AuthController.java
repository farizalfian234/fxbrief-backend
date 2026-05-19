package com.fxbrief.auth.controller;

import com.fxbrief.auth.dto.CancelDeletionResponse;
import com.fxbrief.auth.dto.ForgotPasswordRequest;
import com.fxbrief.auth.dto.GoogleLoginRequest;
import com.fxbrief.auth.dto.LoginRequest;
import com.fxbrief.auth.dto.LoginResponse;
import com.fxbrief.auth.dto.MessageResponse;
import com.fxbrief.auth.dto.RegisterRequest;
import com.fxbrief.auth.dto.RegisterResponse;
import com.fxbrief.auth.dto.RequestDeletionResponse;
import com.fxbrief.auth.dto.ResetPasswordRequest;
import com.fxbrief.auth.dto.VerifyEmailRequest;
import com.fxbrief.auth.security.AuthenticatedUser;
import com.fxbrief.auth.service.AccountDeletionService;
import com.fxbrief.auth.service.EmailVerificationService;
import com.fxbrief.auth.service.GoogleAuthService;
import com.fxbrief.auth.service.LoginService;
import com.fxbrief.auth.service.PasswordResetService;
import com.fxbrief.auth.service.RegistrationService;
import com.fxbrief.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RegistrationService registrationService;
    private final EmailVerificationService emailVerificationService;
    private final LoginService loginService;
    private final GoogleAuthService googleAuthService;
    private final PasswordResetService passwordResetService;
    private final AccountDeletionService accountDeletionService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(@Valid @RequestBody RegisterRequest request) {
        RegisterResponse response = registrationService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponse<MessageResponse>> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return ResponseEntity.ok(ApiResponse.success(emailVerificationService.verify(request)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(loginService.login(request)));
    }

    @PostMapping("/google")
    public ResponseEntity<ApiResponse<LoginResponse>> google(@Valid @RequestBody GoogleLoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(googleAuthService.login(request)));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<MessageResponse>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        return ResponseEntity.ok(ApiResponse.success(passwordResetService.requestReset(request)));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<MessageResponse>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        return ResponseEntity.ok(ApiResponse.success(passwordResetService.resetPassword(request)));
    }

    @PostMapping("/request-deletion")
    public ResponseEntity<ApiResponse<RequestDeletionResponse>> requestDeletion(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(ApiResponse.success(accountDeletionService.requestDeletion(principal)));
    }

    @PostMapping("/cancel-deletion")
    public ResponseEntity<ApiResponse<CancelDeletionResponse>> cancelDeletion(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(ApiResponse.success(accountDeletionService.cancelDeletion(principal)));
    }
}
