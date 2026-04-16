package com.sentinelpay.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.web.client.RestClient;

/**
 * Redis configuration — sets up RedisTemplate beans and pre-compiled Lua scripts
 * used by the rate limiter and distributed lock services.
 */
@Configuration
public class RedisConfig {

    @Bean
    @Primary
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }

    /**
     * Atomic sliding-window rate limiter script.
     *
     * <p>KEYS[1] = sorted-set key (e.g. "rate_limit:payments:{walletId}")<br>
     * ARGV[1] = current timestamp in milliseconds<br>
     * ARGV[2] = window size in milliseconds<br>
     * ARGV[3] = max allowed requests in window<br>
     * Returns 1 if allowed, 0 if rate-limited.
     */
    @Bean(name = "rateLimiterScript")
    public DefaultRedisScript<Long> rateLimiterScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
                "local key = KEYS[1] " +
                "local now = tonumber(ARGV[1]) " +
                "local window = tonumber(ARGV[2]) " +
                "local max = tonumber(ARGV[3]) " +
                "local cutoff = now - window " +
                "redis.call('ZREMRANGEBYSCORE', key, '-inf', cutoff) " +
                "local count = redis.call('ZCARD', key) " +
                "if count < max then " +
                "  redis.call('ZADD', key, now, now .. '-' .. math.random(1,100000)) " +
                "  redis.call('PEXPIRE', key, window) " +
                "  return 1 " +
                "else " +
                "  return 0 " +
                "end"
        );
        script.setResultType(Long.class);
        return script;
    }

    /**
     * Atomic distributed lock release script — only deletes if token matches.
     *
     * <p>KEYS[1] = lock key<br>
     * ARGV[1] = lock token (UUID)<br>
     * Returns 1 if released, 0 if token mismatch (lock already expired or taken).
     */
    @Bean
    public RestClient restClient() {
        return RestClient.builder()
                .defaultHeader("User-Agent", "SentinelPay-WebhookDispatcher/1.0")
                .build();
    }

    @Bean(name = "unlockScript")
    public DefaultRedisScript<Long> unlockScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "  return redis.call('del', KEYS[1]) " +
                "else " +
                "  return 0 " +
                "end"
        );
        script.setResultType(Long.class);
        return script;
    }
}
