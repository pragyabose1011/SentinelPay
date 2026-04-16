package com.sentinelpay.payment.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for successful authentication (register, login, or token refresh).
 */
@Data
@Builder
public class AuthResponse {

    private String  accessToken;
    private String  refreshToken;
    private String  tokenType;
    private Instant expiresAt;
    private Instant refreshExpiresAt;
    private UUID    userId;
    private String  email;
    private String  role;
}
