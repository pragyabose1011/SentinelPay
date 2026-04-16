package com.sentinelpay.payment.controller;

import com.sentinelpay.payment.domain.Wallet;
import com.sentinelpay.payment.dto.WalletRequest;
import com.sentinelpay.payment.dto.WalletResponse;
import com.sentinelpay.payment.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * REST API for wallet management.
 *
 * <pre>
 * POST   /api/v1/wallets                        — create a wallet
 * GET    /api/v1/wallets/{walletId}             — get a wallet by ID
 * GET    /api/v1/wallets?userId={id}            — list wallets for a user
 * PATCH  /api/v1/wallets/{walletId}/status      — freeze / close / reactivate
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(
            @Valid @RequestBody WalletRequest request,
            @AuthenticationPrincipal UUID actorId) {
        WalletResponse wallet = walletService.createWallet(request, actorId);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(wallet.getId()).toUri();
        return ResponseEntity.created(location).body(wallet);
    }

    @GetMapping("/{walletId}")
    public ResponseEntity<WalletResponse> getWallet(
            @PathVariable UUID walletId,
            @AuthenticationPrincipal UUID actorId) {
        return ResponseEntity.ok(walletService.getWallet(walletId, actorId));
    }

    @GetMapping
    public ResponseEntity<List<WalletResponse>> listWalletsByUser(
            @RequestParam UUID userId,
            @AuthenticationPrincipal UUID actorId) {
        return ResponseEntity.ok(walletService.getWalletsByUser(userId, actorId));
    }

    @PatchMapping("/{walletId}/status")
    public ResponseEntity<WalletResponse> updateStatus(
            @PathVariable UUID walletId,
            @RequestParam Wallet.WalletStatus status,
            @AuthenticationPrincipal UUID actorId) {
        return ResponseEntity.ok(walletService.updateStatus(walletId, status, actorId));
    }
}
