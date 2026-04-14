package com.sentinelpay.payment.service;

import com.sentinelpay.payment.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Redis sliding-window rate limiter.
 *
 * <p>Uses a sorted set per key. On each call:
 * <ol>
 *   <li>Removes entries older than the window.</li>
 *   <li>Counts remaining entries.</li>
 *   <li>If under the limit, adds the current timestamp and returns allowed.</li>
 *   <li>Otherwise throws {@link RateLimitExceededException}.</li>
 * </ol>
 * All three steps run atomically in a single Lua script.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimiterService {

    /** Max payment initiations per wallet per minute. */
    private static final int MAX_PAYMENTS_PER_MINUTE = 20;
    private static final long WINDOW_MS = 60_000L;

    private final RedisTemplate<String, String> redisTemplate;

    @Qualifier("rateLimiterScript")
    private final DefaultRedisScript<Long> rateLimiterScript;

    /**
     * Checks and records a payment attempt for the given sender wallet.
     * Throws {@link RateLimitExceededException} if the limit is exceeded.
     *
     * @param senderWalletId wallet ID used as the rate-limit key
     */
    public void checkPaymentRateLimit(String senderWalletId) {
        String key = "rate_limit:payments:" + senderWalletId;
        checkLimit(key, WINDOW_MS, MAX_PAYMENTS_PER_MINUTE,
                "Payment rate limit exceeded: max " + MAX_PAYMENTS_PER_MINUTE
                        + " payments per minute for wallet " + senderWalletId);
    }

    private void checkLimit(String key, long windowMs, int maxRequests, String errorMessage) {
        try {
            long nowMs = Instant.now().toEpochMilli();
            Long result = redisTemplate.execute(
                    rateLimiterScript,
                    List.of(key),
                    String.valueOf(nowMs),
                    String.valueOf(windowMs),
                    String.valueOf(maxRequests)
            );
            if (result == null || result == 0L) {
                log.warn("Rate limit hit for key={}", key);
                throw new RateLimitExceededException(errorMessage);
            }
        } catch (RateLimitExceededException e) {
            throw e;
        } catch (Exception e) {
            // Redis is unavailable — fail open (log, don't block the payment)
            log.error("Rate limiter Redis error for key={}: {}", key, e.getMessage());
        }
    }
}
