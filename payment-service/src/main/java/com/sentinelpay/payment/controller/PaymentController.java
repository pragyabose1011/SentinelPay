package com.sentinelpay.payment.controller;

import com.sentinelpay.payment.dto.PaymentRequest;
import com.sentinelpay.payment.dto.PaymentResponse;
import com.sentinelpay.payment.dto.ReversalRequest;
import com.sentinelpay.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * REST API for payment transactions.
 *
 * <pre>
 * POST  /api/v1/payments                              — initiate a transfer
 * POST  /api/v1/payments/{transactionId}/reverse      — reverse a completed transaction
 * GET   /api/v1/payments/{transactionId}              — get a single transaction
 * GET   /api/v1/payments?walletId=&cursorCreatedAt=&cursorId= — cursor-paged history
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentResponse> initiatePayment(@Valid @RequestBody PaymentRequest request) {
        PaymentResponse response = paymentService.processPayment(request);
        if (response.isIdempotent()) {
            return ResponseEntity.ok(response);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(response.getTransactionId()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PostMapping("/{transactionId}/reverse")
    public ResponseEntity<PaymentResponse> reverseTransaction(
            @PathVariable UUID transactionId,
            @Valid @RequestBody ReversalRequest request) {
        PaymentResponse response = paymentService.reverseTransaction(transactionId, request);
        if (response.isIdempotent()) {
            return ResponseEntity.ok(response);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/payments/{id}").buildAndExpand(response.getTransactionId()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<PaymentResponse> getTransaction(@PathVariable UUID transactionId) {
        return ResponseEntity.ok(paymentService.getTransaction(transactionId));
    }

    /**
     * Cursor-based paginated history. Omit cursor params on first page.
     * Use the last item's {@code createdAt} and {@code transactionId} as the next cursor.
     */
    @GetMapping
    public ResponseEntity<Page<PaymentResponse>> listTransactions(
            @RequestParam UUID walletId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant cursorCreatedAt,
            @RequestParam(required = false) UUID cursorId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(
                paymentService.getTransactionHistory(walletId, cursorCreatedAt, cursorId, pageable));
    }
}
