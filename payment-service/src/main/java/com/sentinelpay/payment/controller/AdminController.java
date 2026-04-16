package com.sentinelpay.payment.controller;

import com.sentinelpay.payment.domain.AuditLog;
import com.sentinelpay.payment.domain.Transaction;
import com.sentinelpay.payment.domain.User;
import com.sentinelpay.payment.dto.PaymentResponse;
import com.sentinelpay.payment.dto.UserResponse;
import com.sentinelpay.payment.dto.WalletResponse;
import com.sentinelpay.payment.repository.OutboxEventRepository;
import com.sentinelpay.payment.repository.TransactionRepository;
import com.sentinelpay.payment.repository.UserRepository;
import com.sentinelpay.payment.repository.WalletRepository;
import com.sentinelpay.payment.service.AuditLogService;
import com.sentinelpay.payment.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Admin-only REST API — requires {@code ROLE_ADMIN}.
 *
 * <pre>
 * GET    /api/v1/admin/transactions              — all transactions (paginated)
 * GET    /api/v1/admin/users                     — all users (paginated)
 * PATCH  /api/v1/admin/transactions/{id}/unflag  — clear fraud flag on a transaction
 * PATCH  /api/v1/admin/wallets/{id}/status       — freeze / close any wallet
 * GET    /api/v1/admin/outbox/stats              — outbox backlog size
 * GET    /api/v1/admin/audit?entityType=&entityId= — audit history for an entity
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final TransactionRepository transactionRepository;
    private final UserRepository        userRepository;
    private final WalletRepository      walletRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final AuditLogService       auditLogService;
    private final WalletService         walletService;

    @GetMapping("/transactions")
    public ResponseEntity<Page<PaymentResponse>> listAllTransactions(
            @PageableDefault(size = 50, sort = "createdAt") Pageable pageable) {
        Page<PaymentResponse> page = transactionRepository.findAll(pageable)
                .map(PaymentResponse::from);
        return ResponseEntity.ok(page);
    }

    @GetMapping("/users")
    public ResponseEntity<Page<UserResponse>> listAllUsers(
            @PageableDefault(size = 50) Pageable pageable) {
        Page<UserResponse> page = userRepository.findAll(pageable)
                .map(UserResponse::from);
        return ResponseEntity.ok(page);
    }

    /**
     * Clears the fraud flag on a transaction by resetting its metadata.
     * Records an audit entry for compliance.
     */
    @PatchMapping("/transactions/{transactionId}/unflag")
    public ResponseEntity<PaymentResponse> unflagTransaction(
            @PathVariable UUID transactionId,
            @AuthenticationPrincipal UUID actorId) {
        Transaction txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
        String oldMetadata = txn.getMetadata();
        txn.setMetadata("{\"fraud_score\":0,\"risk_level\":\"LOW\",\"signals\":\"admin_cleared\"}");
        transactionRepository.save(txn);
        auditLogService.recordAsync("Transaction", transactionId.toString(),
                "FRAUD_FLAG_CLEARED", actorId, oldMetadata, txn.getMetadata());
        return ResponseEntity.ok(PaymentResponse.from(txn));
    }

    @GetMapping("/wallets")
    public ResponseEntity<Page<WalletResponse>> listAllWallets(
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(walletRepository.findAll(pageable).map(WalletResponse::from));
    }

    @PatchMapping("/wallets/{walletId}/status")
    public ResponseEntity<WalletResponse> adminUpdateWalletStatus(
            @PathVariable UUID walletId,
            @RequestParam com.sentinelpay.payment.domain.Wallet.WalletStatus status,
            @AuthenticationPrincipal UUID actorId) {
        WalletResponse updated = walletService.updateStatus(walletId, status, actorId);
        auditLogService.recordAsync("Wallet", walletId.toString(),
                "STATUS_CHANGE", actorId, null, status.name());
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/outbox/stats")
    public ResponseEntity<OutboxStats> getOutboxStats() {
        long pending   = outboxEventRepository.countByProcessedFalse();
        long processed = outboxEventRepository.countByProcessedTrue();
        return ResponseEntity.ok(new OutboxStats(pending, processed));
    }

    @GetMapping("/audit")
    public ResponseEntity<Page<AuditLog>> getAuditHistory(
            @RequestParam String entityType,
            @RequestParam String entityId,
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(auditLogService.getEntityHistory(entityType, entityId, pageable));
    }

    @PatchMapping("/users/{userId}/role")
    public ResponseEntity<UserResponse> updateUserRole(
            @PathVariable UUID userId,
            @RequestParam User.UserRole role,
            @AuthenticationPrincipal UUID actorId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        String oldRole = user.getRole().name();
        user.setRole(role);
        userRepository.save(user);
        auditLogService.recordAsync("User", userId.toString(), "ROLE_CHANGE", actorId, oldRole, role.name());
        return ResponseEntity.ok(UserResponse.from(user));
    }

    public record OutboxStats(long pendingEvents, long processedEvents) {}
}
