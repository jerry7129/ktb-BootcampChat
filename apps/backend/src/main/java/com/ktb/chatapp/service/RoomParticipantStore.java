package com.ktb.chatapp.service;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/** Redis read model for fast room participant counts. MongoDB remains the source of truth. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomParticipantStore {

    private static final String KEY_PREFIX = "room:";
    private static final String KEY_SUFFIX = ":participants";

    private final RedisTemplate<String, Object> redisTemplate;

    private String key(String roomId) {
        return KEY_PREFIX + "{" + roomId + "}" + KEY_SUFFIX;
    }

    public void add(String roomId, String userId) {
        try {
            redisTemplate.opsForSet().add(key(roomId), userId);
        } catch (Exception e) {
            log.warn("Redis 참가자 캐시 추가 실패: roomId={}, userId={}", roomId, userId, e);
        }
    }

    public void remove(String roomId, String userId) {
        try {
            redisTemplate.opsForSet().remove(key(roomId), userId);
        } catch (Exception e) {
            log.warn("Redis 참가자 캐시 제거 실패: roomId={}, userId={}", roomId, userId, e);
        }
    }

    public long count(String roomId, Set<String> sourceParticipantIds) {
        try {
            String key = key(roomId);
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(key)) && !sourceParticipantIds.isEmpty()) {
                redisTemplate.opsForSet().add(key, sourceParticipantIds.toArray());
            }
            Long count = redisTemplate.opsForSet().size(key);
            return count != null ? count : sourceParticipantIds.size();
        } catch (Exception e) {
            log.warn("Redis 참가자 수 조회 실패, MongoDB 값 사용: roomId={}", roomId, e);
            return sourceParticipantIds.size();
        }
    }
}
