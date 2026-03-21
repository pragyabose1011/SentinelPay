package com.sentinelpay.payment.controller;

import com.sentinelpay.payment.domain.Wallet;
import com.sentinelpay.payment.dto.WalletResponse;
import com.sentinelpay.payment.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST API for wallet queries.
 *
 * <pre>
 * GET  /api/v1/wallets/{walletId}          — get a single wallet
 * GET  /api/v1/wallets?userId=&lt;id&gt;         — list wallets for a user
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletRepository walletRepository;

    /**
     * Returns a specific wallet by its ID.
     */
    @GetMapping("/{walletId}")
    public ResponseEntity<WalletResponse> getWallet(@PathVariable UUID walletId) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + walletId));
        return ResponseEntity.ok(WalletResponse.from(wallet));
    }

    /**
     * Returns all wallets belonging to a user.
     */
    @GetMapping
    public ResponseEntity<List<WalletResponse>> listWalletsByUser(@RequestParam UUID userId) {
        List<WalletResponse> wallets = walletRepository.findByUserId(userId)
                .stream()
                .map(WalletResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(wallets);
    }
}
