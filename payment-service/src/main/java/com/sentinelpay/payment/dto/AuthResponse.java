package com.sentinelpay.payment.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for successful authentication (register or login).
 */
@Data
@Builder
public class AuthResponse {

    private String accessToken;
    private String tokenType;
    private Instant expiresAt;
    private UUID userId;
    private String email;
    private String role;
}
