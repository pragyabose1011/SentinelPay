package com.sentinelpay.payment.controller;

import com.sentinelpay.payment.dto.*;
import com.sentinelpay.payment.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Public and auth-protected authentication endpoints.
 *
 * <pre>
 * POST /api/v1/auth/register         — create account, receive token pair
 * POST /api/v1/auth/login            — authenticate, receive token pair
 * POST /api/v1/auth/refresh          — exchange refresh token for new pair
 * POST /api/v1/auth/logout           — revoke tokens (requires valid access token)
 * POST /api/v1/auth/forgot-password  — request password reset link
 * POST /api/v1/auth/reset-password   — apply new password using reset token
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refresh(request.getRefreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            HttpServletRequest request,
            @RequestBody(required = false) LogoutRequest body) {

        String accessToken = extractBearerToken(request);
        String refreshToken = (body != null) ? body.getRefreshToken() : null;

        if (accessToken != null) {
            authService.logout(accessToken, refreshToken);
        }
        return ResponseEntity.ok(Map.of("message", "Logged out successfully."));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.getEmail());
        // Always 200 — do not reveal whether the email exists
        return ResponseEntity.ok(Map.of("message",
                "If that email is registered you will receive a reset link shortly."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.getToken(), request.getNewPassword());
        return ResponseEntity.ok(Map.of("message", "Password updated successfully."));
    }

    // -------------------------------------------------------------------------

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
