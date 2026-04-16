package com.sentinelpay.payment.service;

import com.sentinelpay.payment.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the Redis sliding-window rate limiter.
 * Redis is mocked — no external dependencies.
 *
 * <p>Resilience4j circuit breaker is NOT active in unit tests (no Spring context),
 * so the fallback {@code failOpen} is tested directly via exception stubbing.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
class RateLimiterServiceTest {

    @Mock
    RedisTemplate<String, String> redisTemplate;

    @Mock
    DefaultRedisScript<Long> rateLimiterScript;

    RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        rateLimiterService = new RateLimiterService(redisTemplate, rateLimiterScript);
    }

    // =========================================================================
    // Allowed path
    // =========================================================================

    @Test
    void checkPaymentRateLimit_shouldPass_whenRedisReturns1() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(1L);

        assertThatCode(() -> rateLimiterService.checkPaymentRateLimit("wallet-123"))
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // Rate-limited path
    // =========================================================================

    @Test
    void checkPaymentRateLimit_shouldThrow_whenRedisReturns0() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(0L);

        assertThatThrownBy(() -> rateLimiterService.checkPaymentRateLimit("wallet-456"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("rate limit exceeded");
    }

    @Test
    void checkPaymentRateLimit_shouldThrow_whenRedisReturnsNull() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(null);

        assertThatThrownBy(() -> rateLimiterService.checkPaymentRateLimit("wallet-789"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    // =========================================================================
    // Fail-open (circuit open)
    // =========================================================================

    @Test
    void failOpen_shouldNotThrow_allowingPaymentThrough() {
        // failOpen is the Resilience4j fallback — it must never throw
        assertThatCode(() -> rateLimiterService.failOpen("wallet-999", new RuntimeException("Redis down")))
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // Key construction
    // =========================================================================

    @Test
    void checkPaymentRateLimit_shouldUseWalletIdInRedisKey() {
        String walletId = "test-wallet-id";
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(1L);

        rateLimiterService.checkPaymentRateLimit(walletId);

        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of("rate_limit:payments:" + walletId)),
                anyString(), anyString(), anyString());
    }
}
