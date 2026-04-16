package com.sentinelpay.payment.repository;

import com.sentinelpay.payment.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.userId = :userId")
    void deleteByUserId(UUID userId);

    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.expiresAt < :now")
    void deleteByExpiresAtBefore(Instant now);
}
