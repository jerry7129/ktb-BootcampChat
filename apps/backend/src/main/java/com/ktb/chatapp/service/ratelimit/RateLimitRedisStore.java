package com.ktb.chatapp.service.ratelimit;

import com.ktb.chatapp.model.RateLimit;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis implementation of RateLimitStore.
 *
 * Replaces the MongoDB-backed store: checkRateLimit() runs on every chat
 * message and every @RateLimit-annotated HTTP request, so each read+write
 * was a DB round trip under load (see loadtest/README.md).
 */
@Primary
@Component
@RequiredArgsConstructor
public class RateLimitRedisStore implements RateLimitStore {

    private static final String KEY_PREFIX = "ratelimit:";

    private final RedisTemplate<String, Object> redisTemplate;

    private String key(String clientId) {
        return KEY_PREFIX + clientId;
    }

    @Override
    public Optional<RateLimit> findByClientId(String clientId) {
        Object value = redisTemplate.opsForValue().get(key(clientId));
        if (value instanceof RateLimit rateLimit) {
            return Optional.of(rateLimit);
        }
        return Optional.empty();
    }

    @Override
    public RateLimit save(RateLimit rateLimit) {
        Duration ttl = Duration.between(Instant.now(), rateLimit.getExpiresAt());
        if (ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofSeconds(1);
        }
        redisTemplate.opsForValue().set(key(rateLimit.getClientId()), rateLimit, ttl);
        return rateLimit;
    }
}
