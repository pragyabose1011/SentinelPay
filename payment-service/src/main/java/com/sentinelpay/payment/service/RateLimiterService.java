package com.sentinelpay.payment.service;

import com.sentinelpay.payment.exception.RateLimitExceededException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
     * When {@code true} (default), Redis unavailability allows requests through.
     * Set to {@code false} in environments where strict rate enforcement is required
     * even at the cost of availability during Redis outages.
     */
    @Value("${sentinelpay.rate-limiter.fail-open:true}")
    private boolean failOpen = true;  // default allows fail-open without Spring context

    /**
     * Checks and records a payment attempt for the given sender wallet.
     *
     * @throws RateLimitExceededException if the limit is exceeded
     */
    @CircuitBreaker(name = "redis", fallbackMethod = "rateLimitFallback")
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

    /**
     * Fallback when the Redis circuit is open.
     * Behaviour is controlled by {@code sentinelpay.rate-limiter.fail-open}:
     * <ul>
     *   <li>{@code true} (default) — allow the request through (availability over safety).</li>
     *   <li>{@code false} — reject the request to prevent unchecked traffic (safety over availability).</li>
     * </ul>
     */
    @SuppressWarnings("unused")
    public void rateLimitFallback(String senderWalletId, Throwable t) {
        if (failOpen) {
            log.error("Rate limiter circuit open for wallet={} — failing open: {}", senderWalletId, t.getMessage());
        } else {
            log.error("Rate limiter circuit open for wallet={} — failing closed: {}", senderWalletId, t.getMessage());
            throw new RateLimitExceededException("Rate limiter unavailable — request rejected.");
        }
    }
}
