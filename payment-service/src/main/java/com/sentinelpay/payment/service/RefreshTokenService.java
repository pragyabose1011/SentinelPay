package com.sentinelpay.payment.service;

import com.sentinelpay.payment.domain.RefreshToken;
import com.sentinelpay.payment.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Manages opaque refresh tokens stored as SHA-256 hashes.
 *
 * <p>The raw token is a random UUID string returned to the client once.
 * Only its SHA-256 hash is persisted — if the DB is compromised the raw
 * tokens cannot be reconstructed or replayed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${sentinelpay.jwt.refresh-expiration-ms:604800000}")
    private long refreshExpirationMs;

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    /**
     * Generates a new refresh token for the given user and persists its hash.
     *
     * @return the raw (plaintext) token to return to the client — not stored anywhere
     */
    @Transactional
    public String createRefreshToken(UUID userId) {
        String rawToken = UUID.randomUUID().toString();
        RefreshToken entity = RefreshToken.builder()
                .userId(userId)
                .tokenHash(hash(rawToken))
                .expiresAt(Instant.now().plusMillis(refreshExpirationMs))
                .build();
        refreshTokenRepository.saveAndFlush(entity);
        log.debug("Refresh token created for userId={}", userId);
        return rawToken;
    }

    // -------------------------------------------------------------------------
    // Rotate (validate old → issue new)
    // -------------------------------------------------------------------------

    /**
     * Validates the incoming refresh token, deletes it (one-time use), and
     * returns a new raw token for the same user.
     *
     * @throws IllegalArgumentException if the token is not found or expired
     */
    @Transactional
    public RotationResult rotate(String rawToken) {
        String hash = hash(rawToken);
        RefreshToken existing = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token."));

        if (existing.getExpiresAt().isBefore(Instant.now())) {
            refreshTokenRepository.delete(existing);
            throw new IllegalArgumentException("Refresh token has expired. Please log in again.");
        }

        UUID userId = existing.getUserId();
        refreshTokenRepository.delete(existing);

        String newRaw = createRefreshToken(userId);
        log.debug("Refresh token rotated for userId={}", userId);
        return new RotationResult(userId, newRaw);
    }

    // -------------------------------------------------------------------------
    // Revoke
    // -------------------------------------------------------------------------

    /** Deletes all refresh tokens for a user (called on logout or password change). */
    @Transactional
    public void revokeAllForUser(UUID userId) {
        refreshTokenRepository.deleteByUserId(userId);
        log.debug("All refresh tokens revoked for userId={}", userId);
    }

    /** Revokes a single refresh token by its raw value. */
    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken))
                .ifPresent(refreshTokenRepository::delete);
    }

    // -------------------------------------------------------------------------
    // Housekeeping
    // -------------------------------------------------------------------------

    /** Purges expired tokens daily at 03:00. */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeExpired() {
        refreshTokenRepository.deleteByExpiresAtBefore(Instant.now());
        log.info("Purged expired refresh tokens");
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    public static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record RotationResult(UUID userId, String newRawToken) {}
}
