package com.sentinelpay.payment.controller;

import com.sentinelpay.payment.domain.User;
import com.sentinelpay.payment.dto.UserRequest;
import com.sentinelpay.payment.dto.UserResponse;
import com.sentinelpay.payment.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST API for user account management.
 *
 * <pre>
 * POST   /api/v1/users                          — admin: create user without password
 * GET    /api/v1/users/{userId}                 — get user by ID
 * GET    /api/v1/users?email={email}            — find user by email
 * PATCH  /api/v1/users/{userId}/kyc             — mark KYC as verified (self or admin)
 * PATCH  /api/v1/users/{userId}/status          — update account status (self-close or admin)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody UserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.createUser(request));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserResponse> getUser(@PathVariable UUID userId) {
        return ResponseEntity.ok(userService.getUser(userId));
    }

    @GetMapping
    public ResponseEntity<UserResponse> getUserByEmail(@RequestParam String email) {
        return ResponseEntity.ok(userService.getUserByEmail(email));
    }

    @PatchMapping("/{userId}/kyc")
    public ResponseEntity<UserResponse> verifyKyc(
            @PathVariable UUID userId,
            @AuthenticationPrincipal UUID actorId) {
        return ResponseEntity.ok(userService.verifyKyc(userId, actorId));
    }

    @PatchMapping("/{userId}/status")
    public ResponseEntity<UserResponse> updateStatus(
            @PathVariable UUID userId,
            @RequestParam User.UserStatus status,
            @AuthenticationPrincipal UUID actorId) {
        return ResponseEntity.ok(userService.updateStatus(userId, status, actorId));
    }
}
