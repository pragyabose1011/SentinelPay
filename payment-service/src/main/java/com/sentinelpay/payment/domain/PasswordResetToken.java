package com.sentinelpay.payment.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Single-use, time-limited token for the password reset flow.
 *
 * <p>Only the SHA-256 hash of the raw token is stored. Once consumed,
 * {@code used} is set to {@code true} and the token cannot be reused.
 */
@Entity
@Table(name = "password_reset_tokens", indexes = {
        @Index(name = "idx_password_reset_user",    columnList = "user_id"),
        @Index(name = "idx_password_reset_expires",  columnList = "expires_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Hex-encoded SHA-256 of the raw token sent to the user. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used", nullable = false)
    @Builder.Default
    private boolean used = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
