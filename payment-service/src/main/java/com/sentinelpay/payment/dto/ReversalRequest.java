package com.sentinelpay.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for reversing a completed transaction.
 */
@Data
public class ReversalRequest {

    /**
     * Client-generated idempotency key for this reversal — prevents double-reversal
     * if the request is retried.
     */
    @NotBlank(message = "Idempotency key is required")
    @Size(max = 128, message = "Idempotency key must not exceed 128 characters")
    private String idempotencyKey;

    @Size(max = 500, message = "Reason must not exceed 500 characters")
    private String reason;
}
