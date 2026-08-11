package com.ktb.chatapp.service;

import com.ktb.chatapp.event.RoomActivityEvent;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 새 메시지가 저장되면 채팅방 목록의 활성도 지표를 갱신하도록 알린다.
 *
 * 메시지 하나마다 MongoDB count + room-list(채팅방 목록을 보고 있는 전체 유저) 브로드캐스트를
 * 반복하면 유저 수 × 메시지 수만큼 부하가 곱연산으로 커진다. room-list 표시는 실시간일 필요가
 * 없고(프론트에 30초 폴백 폴링이 이미 있음) 참가자에게 가는 채팅 메시지 자체와는 무관한
 * 부가 지표이므로, 방 하나당 최소 갱신 간격을 두고 그 사이 발생한 메시지는 건너뛴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomActivityNotifier {

    private static final String THROTTLE_KEY_PREFIX = "roomActivityThrottle:";

    private final RecentMessageCounter recentMessageCounter;
    private final ApplicationEventPublisher eventPublisher;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${chatapp.room-activity.throttle-ms:3000}")
    private long throttleMs;

    public void notifyMessageStored(String roomId) {
        if (roomId == null) {
            return;
        }

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(THROTTLE_KEY_PREFIX + roomId, "1", Duration.ofMillis(throttleMs));
            if (!Boolean.TRUE.equals(acquired)) {
                return;
            }

            int recentMessageCount = recentMessageCounter.countRecentMessages(roomId);
            eventPublisher.publishEvent(new RoomActivityEvent(this, roomId, recentMessageCount));
        } catch (Exception e) {
            log.error("roomActivity 이벤트 발행 실패: roomId={}", roomId, e);
        }
    }
}
