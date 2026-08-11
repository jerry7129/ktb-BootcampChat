package com.ktb.chatapp.websocket.socketio;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Redis-backed implementation of ChatDataStore.
 *
 * Replaces LocalChatDataStore's process-local ConcurrentHashMap so
 * ConnectedUsers/UserRooms bookkeeping is shared across backend instances,
 * matching the multi-node Socket.IO broadcast set up in SocketIOConfig
 * (RedissonStoreFactory). Entries carry a safety TTL since, unlike the old
 * in-memory map, Redis data doesn't automatically disappear when an
 * instance crashes without cleanly disconnecting its clients.
 */
public class RedisChatDataStore implements ChatDataStore {

    private static final String KEY_PREFIX = "chatdata:";
    private static final String CONNECTED_USER_PREFIX = "conn_users:userid:";
    private static final String CONNECTED_USERS_KEY = "chatdata:connected-users";
    private static final Duration TTL = Duration.ofHours(6);

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisChatDataStore(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private String key(String key) {
        return KEY_PREFIX + key;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = redisTemplate.opsForValue().get(key(key));
        if (type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        return Optional.empty();
    }

    @Override
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key(key), value, TTL);
        if (key.startsWith(CONNECTED_USER_PREFIX)) {
            redisTemplate.opsForSet().add(CONNECTED_USERS_KEY, key);
        }
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(key(key));
        if (key.startsWith(CONNECTED_USER_PREFIX)) {
            redisTemplate.opsForSet().remove(CONNECTED_USERS_KEY, key);
        }
    }

    @Override
    public int size() {
        Long count = redisTemplate.opsForSet().size(CONNECTED_USERS_KEY);
        return count != null ? Math.toIntExact(count) : 0;
    }
}
