package com.sentinelpay.payment.repository;

import com.sentinelpay.payment.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHashAndUsedFalse(String tokenHash);

    @Modifying
    @Query("DELETE FROM PasswordResetToken t WHERE t.expiresAt < :now")
    void deleteByExpiresAtBefore(Instant now);
}
