package com.sentinelpay.payment.dto;

import com.sentinelpay.payment.domain.Transaction;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response body returned by the Payment API for a transaction.
 */
@Data
@Builder
public class PaymentResponse {

    private UUID transactionId;
    private String idempotencyKey;
    private UUID senderWalletId;
    private UUID receiverWalletId;
    private BigDecimal amount;
    private String currency;
    private Transaction.TransactionStatus status;
    private Transaction.TransactionType type;
    private String description;
    private String failureReason;
    private Instant createdAt;
    private Instant completedAt;

    /**
     * True when the response was served from idempotency cache (no re-processing).
     */
    private boolean idempotent;

    public static PaymentResponse from(Transaction transaction) {
        return PaymentResponse.builder()
                .transactionId(transaction.getId())
                .idempotencyKey(transaction.getIdempotencyKey())
                .senderWalletId(transaction.getSenderWallet().getId())
                .receiverWalletId(transaction.getReceiverWallet().getId())
                .amount(transaction.getAmount())
                .currency(transaction.getCurrency())
                .status(transaction.getStatus())
                .type(transaction.getType())
                .description(transaction.getDescription())
                .failureReason(transaction.getFailureReason())
                .createdAt(transaction.getCreatedAt())
                .completedAt(transaction.getCompletedAt())
                .idempotent(false)
                .build();
    }
}
