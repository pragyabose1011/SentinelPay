package com.sentinelpay.payment.dto;

import com.sentinelpay.payment.domain.Wallet;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response body for wallet queries.
 */
@Data
@Builder
public class WalletResponse {

    private UUID id;
    private UUID userId;
    private String currency;
    private BigDecimal balance;
    private Wallet.WalletStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    public static WalletResponse from(Wallet wallet) {
        return WalletResponse.builder()
                .id(wallet.getId())
                .userId(wallet.getUser().getId())
                .currency(wallet.getCurrency())
                .balance(wallet.getBalance())
                .status(wallet.getStatus())
                .createdAt(wallet.getCreatedAt())
                .updatedAt(wallet.getUpdatedAt())
                .build();
    }
}
