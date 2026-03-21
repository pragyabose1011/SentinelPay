package com.sentinelpay.payment.dto;

import com.sentinelpay.payment.domain.Transaction;
import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Incoming request body for initiating a payment transfer.
 */
@Data
@Builder
public class PaymentRequest {

    /**
     * Client-generated idempotency key — must be unique per payment attempt.
     * Re-submitting the same key returns the original response without re-processing.
     */
    @NotBlank(message = "Idempotency key is required")
    @Size(max = 128, message = "Idempotency key must not exceed 128 characters")
    private String idempotencyKey;

    @NotNull(message = "Sender wallet ID is required")
    private UUID senderWalletId;

    @NotNull(message = "Receiver wallet ID is required")
    private UUID receiverWalletId;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.0001", message = "Amount must be greater than zero")
    @Digits(integer = 15, fraction = 4, message = "Amount must have at most 15 integer and 4 decimal digits")
    private BigDecimal amount;

    @NotBlank(message = "Currency is required")
    @Size(min = 3, max = 3, message = "Currency must be a 3-letter ISO code")
    private String currency;

    @NotNull(message = "Transaction type is required")
    private Transaction.TransactionType type;

    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;
}
