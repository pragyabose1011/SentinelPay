package com.sentinelpay.payment.service;

import com.sentinelpay.payment.exception.RateLimitExceededException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Redis sliding-window rate limiter with Resilience4j circuit breaker.
 *
 * <p>If Redis is unavailable the circuit opens and the fallback {@code failOpen}
 * allows the request through rather than blocking legitimate payments.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimiterService {

    private static final int  MAX_PAYMENTS_PER_MINUTE = 20;
    private static final long WINDOW_MS               = 60_000L;

    private final RedisTemplate<String, String> redisTemplate;

    @Qualifier("rateLimiterScript")
    private final DefaultRedisScript<Long> rateLimiterScript;

    /**
     * Checks and records a payment attempt for the given sender wallet.
     *
     * @throws RateLimitExceededException if the limit is exceeded
     */
    @CircuitBreaker(name = "redis", fallbackMethod = "failOpen")
    public void checkPaymentRateLimit(String senderWalletId) {
        String key   = "rate_limit:payments:" + senderWalletId;
        long   nowMs = Instant.now().toEpochMilli();

        Long result = redisTemplate.execute(
                rateLimiterScript,
                List.of(key),
                String.valueOf(nowMs),
                String.valueOf(WINDOW_MS),
                String.valueOf(MAX_PAYMENTS_PER_MINUTE)
        );

        if (result == null || result == 0L) {
            log.warn("Rate limit exceeded for wallet={}", senderWalletId);
            throw new RateLimitExceededException(
                    "Payment rate limit exceeded: max " + MAX_PAYMENTS_PER_MINUTE
                            + " payments per minute.");
        }
    }

    /** Fallback: fail open — Redis is down, allow the payment through. */
    @SuppressWarnings("unused")
    public void failOpen(String senderWalletId, Throwable t) {
        log.error("Rate limiter circuit open for wallet={} — failing open: {}", senderWalletId, t.getMessage());
    }
}
