package com.sentinelpay.payment.controller;

import com.sentinelpay.payment.dto.DepositRequest;
import com.sentinelpay.payment.dto.PaymentResponse;
import com.sentinelpay.payment.dto.WithdrawalRequest;
import com.sentinelpay.payment.service.DepositWithdrawalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * REST API for wallet funding and withdrawals.
 *
 * <pre>
 * POST /api/v1/wallets/deposit    — credit a wallet from an external source
 * POST /api/v1/wallets/withdraw   — debit a wallet to an external destination
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class DepositWithdrawalController {

    private final DepositWithdrawalService depositWithdrawalService;

    @PostMapping("/deposit")
    public ResponseEntity<PaymentResponse> deposit(
            @Valid @RequestBody DepositRequest request,
            @AuthenticationPrincipal UUID actorId) {
        PaymentResponse response = depositWithdrawalService.deposit(request, actorId);
        if (response.isIdempotent()) return ResponseEntity.ok(response);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/payments/{id}").buildAndExpand(response.getTransactionId()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PostMapping("/withdraw")
    public ResponseEntity<PaymentResponse> withdraw(
            @Valid @RequestBody WithdrawalRequest request,
            @AuthenticationPrincipal UUID actorId) {
        PaymentResponse response = depositWithdrawalService.withdraw(request, actorId);
        if (response.isIdempotent()) return ResponseEntity.ok(response);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/payments/{id}").buildAndExpand(response.getTransactionId()).toUri();
        return ResponseEntity.created(location).body(response);
    }
}
