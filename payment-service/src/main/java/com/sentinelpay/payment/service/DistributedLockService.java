package com.sentinelpay.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed distributed lock service.
 *
 * <p>Uses SET key value NX PX ttl for acquisition and an atomic Lua script for release.
 * This prevents the classic race condition where a lock expires between the GET check
 * and the DEL call.
 *
 * <p>Intended use: serialise concurrent payment requests for the same sender wallet
 * at the application layer, complementing the DB-level pessimistic write lock.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DistributedLockService {

    private static final String LOCK_PREFIX = "lock:wallet:";

    private final RedisTemplate<String, String> redisTemplate;

    @Qualifier("unlockScript")
    private final DefaultRedisScript<Long> unlockScript;

    /**
     * Attempts to acquire a lock for the given wallet ID.
     *
     * @param walletId  the wallet to lock
     * @param ttl       lock time-to-live
     * @param unit      TTL time unit
     * @return a lock token (UUID string) to use when releasing, or {@code null} if the lock
     *         could not be acquired (another holder owns it)
     */
    public String tryLock(String walletId, long ttl, TimeUnit unit) {
        String key = LOCK_PREFIX + walletId;
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl, unit);
        if (Boolean.TRUE.equals(acquired)) {
            log.debug("Distributed lock acquired: key={}", key);
            return token;
        }
        log.warn("Could not acquire distributed lock: key={}", key);
        return null;
    }

    /**
     * Releases the lock only if the supplied token matches the stored value.
     * Safe to call even if the lock has already expired.
     *
     * @param walletId  the wallet whose lock to release
     * @param token     the token returned by {@link #tryLock}
     */
    public void unlock(String walletId, String token) {
        if (token == null) return;
        String key = LOCK_PREFIX + walletId;
        try {
            Long released = redisTemplate.execute(
                    unlockScript,
                    List.of(key),
                    token
            );
            if (released != null && released == 1L) {
                log.debug("Distributed lock released: key={}", key);
            } else {
                log.warn("Distributed lock release skipped (token mismatch or expired): key={}", key);
            }
        } catch (Exception e) {
            log.error("Failed to release distributed lock key={}: {}", key, e.getMessage());
        }
    }
}
