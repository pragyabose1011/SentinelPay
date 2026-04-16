package com.sentinelpay.payment.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * Generates and validates HS256-signed JWTs.
 *
 * <p>Access token claims:
 * <ul>
 *   <li>{@code sub}   — user UUID</li>
 *   <li>{@code email} — user email</li>
 *   <li>{@code role}  — USER or ADMIN</li>
 *   <li>{@code jti}   — unique token ID (used for revocation blocklist)</li>
 * </ul>
 *
 * <p>Fail-fast: throws {@link IllegalStateException} on startup if the secret
 * is blank or shorter than 32 characters.
 */
@Component
@Slf4j
public class JwtTokenProvider {

    private final SecretKey signingKey;
    private final long      expirationMs;

    public JwtTokenProvider(
            @Value("${sentinelpay.jwt.secret}") String secret,
            @Value("${sentinelpay.jwt.expiration-ms:900000}") long expirationMs) {

        Assert.hasText(secret,
                "sentinelpay.jwt.secret must not be blank — set JWT_SECRET env var");
        Assert.isTrue(secret.length() >= 32,
                "sentinelpay.jwt.secret must be at least 32 characters — " +
                "current length: " + secret.length());

        this.signingKey   = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    // -------------------------------------------------------------------------
    // Token generation
    // -------------------------------------------------------------------------

    public String generateToken(UserPrincipal principal) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        String jti  = UUID.randomUUID().toString();

        return Jwts.builder()
                .id(jti)
                .subject(principal.getId().toString())
                .claim("email", principal.getEmail())
                .claim("role", principal.getAuthorities().iterator().next().getAuthority()
                        .replace("ROLE_", ""))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    // -------------------------------------------------------------------------
    // Claims extraction
    // -------------------------------------------------------------------------

    public UUID extractUserId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    public String extractRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    /** Returns the JWT ID (jti) — used as the blocklist key on logout. */
    public String extractJti(String token) {
        return parseClaims(token).getId();
    }

    /**
     * Returns how many milliseconds remain before this token expires.
     * Returns 0 if the token is already expired.
     */
    public long getRemainingValidityMs(String token) {
        Date expiry = parseClaims(token).getExpiration();
        long remaining = expiry.getTime() - System.currentTimeMillis();
        return Math.max(remaining, 0);
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    public boolean validate(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT: {}", e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
