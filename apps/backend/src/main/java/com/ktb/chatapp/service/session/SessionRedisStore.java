package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis implementation of SessionStore.
 *
 * Replaces the MongoDB-backed store: validateSession() runs on every socket
 * handshake, every chat message, and every JWT-authenticated HTTP request, so
 * each check+refresh was a DB round trip under load (see loadtest/README.md).
 * Single active session per user, keyed by userId, with a Redis TTL matching
 * Session.expiresAt instead of the old TTL-index + app-level check combo.
 */
@Primary
@Component
@RequiredArgsConstructor
public class SessionRedisStore implements SessionStore {

    private static final String KEY_PREFIX = "session:user:";

    private final RedisTemplate<String, Object> redisTemplate;

    private String key(String userId) {
        return KEY_PREFIX + userId;
    }

    @Override
    public Optional<Session> findByUserId(String userId) {
        Object value = redisTemplate.opsForValue().get(key(userId));
        if (value instanceof Session session) {
            return Optional.of(session);
        }
        return Optional.empty();
    }

    @Override
    public Session save(Session session) {
        Duration ttl = Duration.between(Instant.now(), session.getExpiresAt());
        if (ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofSeconds(1);
        }
        redisTemplate.opsForValue().set(key(session.getUserId()), session, ttl);
        return session;
    }

    @Override
    public void delete(String userId, String sessionId) {
        findByUserId(userId)
            .filter(session -> sessionId.equals(session.getSessionId()))
            .ifPresent(session -> redisTemplate.delete(key(userId)));
    }

    @Override
    public void deleteAll(String userId) {
        redisTemplate.delete(key(userId));
    }
}
