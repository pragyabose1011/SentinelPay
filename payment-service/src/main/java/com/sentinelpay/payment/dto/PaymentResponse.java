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

    /** Populated for REVERSAL transactions — points to the original transaction. */
    private UUID referenceTransactionId;

    /** Fraud score 0–100 assigned at payment time. Null for idempotent replays. */
    private Integer fraudScore;

    /** LOW, MEDIUM, or HIGH. Null for idempotent replays. */
    private String fraudRiskLevel;

    private Instant createdAt;
    private Instant completedAt;

    /** True when the response was served from idempotency cache (no re-processing). */
    private boolean idempotent;

    public static PaymentResponse from(Transaction t) {
        return PaymentResponse.builder()
                .transactionId(t.getId())
                .idempotencyKey(t.getIdempotencyKey())
                .senderWalletId(t.getSenderWallet()   != null ? t.getSenderWallet().getId()   : null)
                .receiverWalletId(t.getReceiverWallet() != null ? t.getReceiverWallet().getId() : null)
                .amount(t.getAmount())
                .currency(t.getCurrency())
                .status(t.getStatus())
                .type(t.getType())
                .description(t.getDescription())
                .failureReason(t.getFailureReason())
                .referenceTransactionId(t.getReferenceTransactionId())
                .createdAt(t.getCreatedAt())
                .completedAt(t.getCompletedAt())
                .idempotent(false)
                .build();
    }
}
