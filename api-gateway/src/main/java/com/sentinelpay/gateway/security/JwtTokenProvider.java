package com.sentinelpay.gateway.security;

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
import java.util.UUID;

/**
 * Stateless JWT validator shared across the gateway.
 * Must use the same secret as payment-service so issued tokens are accepted.
 * Fails fast on startup if the secret is blank or too short.
 */
@Component
@Slf4j
public class JwtTokenProvider {

    private final SecretKey secretKey;

    public JwtTokenProvider(@Value("${sentinelpay.jwt.secret}") String secret) {
        Assert.hasText(secret,
                "sentinelpay.jwt.secret must not be blank — set JWT_SECRET env var");
        Assert.isTrue(secret.length() >= 32,
                "sentinelpay.jwt.secret must be at least 32 characters");
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns true if the token is structurally valid and not expired. */
    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    public UUID getUserId(String token) {
        return UUID.fromString(getClaims(token).getSubject());
    }

    public String getRole(String token) {
        return getClaims(token).get("role", String.class);
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
