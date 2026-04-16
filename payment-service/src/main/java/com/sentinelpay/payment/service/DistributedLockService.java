package com.sentinelpay.payment.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
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
 * Redis-backed distributed lock with Resilience4j circuit breaker.
 *
 * <p>If Redis is unavailable the circuit opens and lock acquisition returns
 * a synthetic token so payments can still proceed (DB locks remain the
 * correctness guarantee; this lock is a performance optimisation).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DistributedLockService {

    private static final String LOCK_PREFIX     = "lock:wallet:";
    private static final String BYPASS_TOKEN    = "circuit-open";

    private final RedisTemplate<String, String> redisTemplate;

    @Qualifier("unlockScript")
    private final DefaultRedisScript<Long> unlockScript;

    /**
     * Attempts to acquire a lock for the given wallet.
     *
     * @return a lock token to pass to {@link #unlock}, or {@code null} if another
     *         holder owns the lock
     */
    @CircuitBreaker(name = "redis", fallbackMethod = "acquireFallback")
    public String tryLock(String walletId, long ttl, TimeUnit unit) {
        String  key   = LOCK_PREFIX + walletId;
        String  token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl, unit);
        if (Boolean.TRUE.equals(acquired)) {
            log.debug("Lock acquired: key={}", key);
            return token;
        }
        log.warn("Lock unavailable: key={}", key);
        return null;
    }

    /**
     * Releases the lock only if the token matches.
     */
    @CircuitBreaker(name = "redis", fallbackMethod = "releaseFallback")
    public void unlock(String walletId, String token) {
        if (token == null || BYPASS_TOKEN.equals(token)) return;
        String key = LOCK_PREFIX + walletId;
        try {
            Long released = redisTemplate.execute(unlockScript, List.of(key), token);
            if (released == null || released == 0L) {
                log.warn("Lock release skipped (expired or token mismatch): key={}", key);
            }
        } catch (Exception e) {
            log.error("Failed to release lock key={}: {}", key, e.getMessage());
        }
    }

    /** Circuit open — return a bypass token so the payment can proceed. */
    @SuppressWarnings("unused")
    public String acquireFallback(String walletId, long ttl, TimeUnit unit, Throwable t) {
        log.error("Lock circuit open for wallet={} — bypassing: {}", walletId, t.getMessage());
        return BYPASS_TOKEN;
    }

    @SuppressWarnings("unused")
    public void releaseFallback(String walletId, String token, Throwable t) {
        log.error("Lock release circuit open for wallet={}: {}", walletId, t.getMessage());
    }
}
