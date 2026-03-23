package com.sentinelpay.payment.controller;

import com.sentinelpay.payment.dto.PaymentRequest;
import com.sentinelpay.payment.dto.PaymentResponse;
import com.sentinelpay.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST API for payment transactions.
 *
 * <pre>
 * POST   /api/v1/payments                     — initiate a payment
 * GET    /api/v1/payments/{transactionId}      — get a single transaction
 * GET    /api/v1/payments?walletId=&lt;id&gt;        — list transactions for a wallet
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * Initiates a payment transfer.
     * Returns 200 OK when the request is idempotent (already processed),
     * or 201 Created for a new transaction.
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> initiatePayment(
            @Valid @RequestBody PaymentRequest request) {

        PaymentResponse response = paymentService.processPayment(request);
        HttpStatus status = response.isIdempotent() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * Retrieves a transaction by its ID.
     */
    @GetMapping("/{transactionId}")
    public ResponseEntity<PaymentResponse> getTransaction(
            @PathVariable UUID transactionId) {

        return ResponseEntity.ok(paymentService.getTransaction(transactionId));
    }

    /**
     * Lists paginated transaction history for a given wallet.
     */
    @GetMapping
    public ResponseEntity<Page<PaymentResponse>> listTransactions(
            @RequestParam UUID walletId,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {

        return ResponseEntity.ok(paymentService.getTransactionHistory(walletId, pageable));
    }
}
