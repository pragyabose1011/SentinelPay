package com.sentinelpay.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/**
 * Request body for creating a new wallet.
 */
@Data
public class WalletRequest {

    @NotNull(message = "User ID is required")
    private UUID userId;

    @NotBlank(message = "Currency is required")
    @Size(min = 3, max = 3, message = "Currency must be a 3-letter ISO 4217 code")
    private String currency;
}
