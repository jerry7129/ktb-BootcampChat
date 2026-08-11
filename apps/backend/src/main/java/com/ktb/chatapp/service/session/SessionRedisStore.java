package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
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
    @SuppressWarnings({"rawtypes", "unchecked"})
    public boolean replaceIfMatches(String userId, String expectedSessionId, Session replacement) {
        String redisKey = key(userId);
        Duration ttl = calculateTtl(replacement);

        Boolean replaced = redisTemplate.execute(new SessionCallback<Boolean>() {
                    @Override
                    public Boolean execute(RedisOperations operations) throws DataAccessException {

                        operations.watch(redisKey);

                        Object value = operations.opsForValue().get(redisKey);

                        if (!(value instanceof Session current)
                                || !expectedSessionId.equals(current.getSessionId())) {
                            operations.unwatch();
                            return false;
                        }

                        operations.multi();

                        operations.opsForValue().set(redisKey, replacement, ttl);

                        List<Object> results = operations.exec();

                        // WATCH 이후 값이 바뀌었다면 EXEC가 실패한다.
                        return results != null && !results.isEmpty();
                    }
        });

        return Boolean.TRUE.equals(replaced);
    }

    private Duration calculateTtl(Session session) {
        Duration ttl = Duration.between(Instant.now(), session.getExpiresAt());

        return ttl.isPositive() ? ttl : Duration.ofSeconds(1);
    }

    @Override
    public Session save(Session session) {
        redisTemplate.opsForValue().set(key(session.getUserId()), session, calculateTtl(session));
        return session;
    }

    @Override
    public void delete(String userId, String expectedSessionId) {
        if (userId == null || expectedSessionId == null) {
            return;
        }

        String redisKey = key(userId);

        redisTemplate.execute(new SessionCallback<List<Object>>() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <K, V> List<Object> execute(
                            RedisOperations<K, V> operations
                    ) throws DataAccessException {

                        K watchedKey = (K) redisKey;

                        // 이 키가 변경되는지 감시 시작
                        operations.watch(watchedKey);

                        Object value = operations.opsForValue().get(watchedKey);

                        if (!(value instanceof Session currentSession)) {
                            operations.unwatch();
                            return List.of();
                        }

                        if (!expectedSessionId.equals(currentSession.getSessionId())) {
                            operations.unwatch();
                            return List.of();
                        }

                        // sessionId가 일치할 때만 삭제 예약
                        operations.multi();
                        operations.delete(watchedKey);

                        // WATCH 이후 키가 변경됐다면 삭제가 취소된다.
                        return operations.exec();
                    }
        });
    }

    @Override
    public void deleteAll(String userId) {
        redisTemplate.delete(key(userId));
    }
}
