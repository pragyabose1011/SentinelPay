package com.sentinelpay.notification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors the PaymentResponse shape published to {@code payment.transactions}
 * by payment-service. Unknown fields are ignored for forward-compatibility.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransactionEvent {

    private UUID   transactionId;
    private String idempotencyKey;
    private UUID   senderWalletId;
    private UUID   receiverWalletId;
    private BigDecimal amount;
    private String currency;
    private String status;   // COMPLETED | FAILED | REVERSED
    private String type;     // TRANSFER | DEPOSIT | WITHDRAWAL | REVERSAL
    private String description;
    private String failureReason;
    private UUID   referenceTransactionId;
    private String fraudRiskLevel;
    private Instant createdAt;
    private Instant completedAt;
}
