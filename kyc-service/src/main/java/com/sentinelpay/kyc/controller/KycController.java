package com.sentinelpay.kyc.controller;

import com.sentinelpay.kyc.domain.KycSubmission;
import com.sentinelpay.kyc.dto.KycStatusResponse;
import com.sentinelpay.kyc.dto.KycSubmitRequest;
import com.sentinelpay.kyc.dto.ReviewRequest;
import com.sentinelpay.kyc.security.UserPrincipal;
import com.sentinelpay.kyc.service.KycService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/kyc")
@RequiredArgsConstructor
public class KycController {

    private final KycService kycService;

    // -------------------------------------------------------------------------
    // User endpoints
    // -------------------------------------------------------------------------

    /**
     * Submit KYC documents for review.
     * POST /api/v1/kyc/submit
     */
    @PostMapping("/submit")
    public ResponseEntity<KycStatusResponse> submit(
            @Valid @RequestBody KycSubmitRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(kycService.submit(request, principal.getUserId()));
    }

    /**
     * Check own KYC submission status.
     * GET /api/v1/kyc/status
     */
    @GetMapping("/status")
    public ResponseEntity<KycStatusResponse> getOwnStatus(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(kycService.getStatus(principal.getUserId()));
    }

    /**
     * Get status for any user (own record or admin).
     * GET /api/v1/kyc/status/{userId}
     */
    @GetMapping("/status/{userId}")
    @PreAuthorize("hasRole('ADMIN') or #userId == authentication.principal.userId")
    public ResponseEntity<KycStatusResponse> getStatus(@PathVariable UUID userId) {
        return ResponseEntity.ok(kycService.getStatus(userId));
    }

    // -------------------------------------------------------------------------
    // Admin endpoints
    // -------------------------------------------------------------------------

    /**
     * Admin review queue — paginated list of PENDING submissions.
     * GET /api/v1/kyc/queue?status=PENDING
     */
    @GetMapping("/queue")
    public ResponseEntity<Page<KycStatusResponse>> queue(
            @RequestParam(defaultValue = "PENDING") KycSubmission.Status status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(kycService.listByStatus(status, pageable));
    }

    /**
     * Approve a KYC submission.
     * POST /api/v1/kyc/{submissionId}/approve
     */
    @PostMapping("/{submissionId}/approve")
    public ResponseEntity<KycStatusResponse> approve(
            @PathVariable UUID submissionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(kycService.approve(submissionId, principal.getUserId()));
    }

    /**
     * Reject a KYC submission.
     * POST /api/v1/kyc/{submissionId}/reject
     */
    @PostMapping("/{submissionId}/reject")
    public ResponseEntity<KycStatusResponse> reject(
            @PathVariable UUID submissionId,
            @Valid @RequestBody ReviewRequest body,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(
                kycService.reject(submissionId, principal.getUserId(), body.getReason()));
    }
}
