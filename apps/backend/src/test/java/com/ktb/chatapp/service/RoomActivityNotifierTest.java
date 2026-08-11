package com.ktb.chatapp.service;

import com.ktb.chatapp.event.RoomActivityEvent;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomActivityNotifierTest {

    @Mock private RecentMessageCounter recentMessageCounter;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;

    private RoomActivityNotifier notifier() {
        RoomActivityNotifier notifier =
                new RoomActivityNotifier(recentMessageCounter, eventPublisher, redisTemplate);
        return notifier;
    }

    private void stubThrottle(Boolean... acquiredResults) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        var stub = when(valueOperations.setIfAbsent(anyString(), any(), any(Duration.class)));
        for (Boolean result : acquiredResults) {
            stub = stub.thenReturn(result);
        }
    }

    @Test
    void notifyMessageStored_firstMessageOfRoom_publishesRecentMessageCount() {
        stubThrottle(true);
        when(recentMessageCounter.countRecentMessages("room-1")).thenReturn(7);

        notifier().notifyMessageStored("room-1");

        ArgumentCaptor<RoomActivityEvent> eventCaptor =
                ArgumentCaptor.forClass(RoomActivityEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals("room-1", eventCaptor.getValue().getRoomId());
        assertEquals(7, eventCaptor.getValue().getRecentMessageCount());
    }

    @Test
    void notifyMessageStored_withinThrottleWindow_onlyFirstMessagePublishes() {
        stubThrottle(true, false, false);
        when(recentMessageCounter.countRecentMessages("room-1")).thenReturn(1);
        RoomActivityNotifier notifier = notifier();

        notifier.notifyMessageStored("room-1");
        notifier.notifyMessageStored("room-1");
        notifier.notifyMessageStored("room-1");

        verify(eventPublisher, times(1)).publishEvent(any(RoomActivityEvent.class));
        verify(recentMessageCounter, times(1)).countRecentMessages("room-1");
    }

    @Test
    void notifyMessageStored_afterThrottleWindowExpires_publishesAgain() {
        stubThrottle(true, true);
        when(recentMessageCounter.countRecentMessages("room-1")).thenReturn(1);
        RoomActivityNotifier notifier = notifier();

        notifier.notifyMessageStored("room-1");
        notifier.notifyMessageStored("room-1");

        verify(eventPublisher, times(2)).publishEvent(any(RoomActivityEvent.class));
    }

    @Test
    void notifyMessageStored_nullRoomId_doesNothing() {
        notifier().notifyMessageStored(null);

        verifyNoInteractions(recentMessageCounter);
        verifyNoInteractions(redisTemplate);
        verify(eventPublisher, never()).publishEvent(any(RoomActivityEvent.class));
    }

    @Test
    void notifyMessageStored_counterFails_swallowsException() {
        stubThrottle(true);
        when(recentMessageCounter.countRecentMessages("room-1"))
                .thenThrow(new RuntimeException("mongo down"));

        notifier().notifyMessageStored("room-1");

        verify(eventPublisher, never()).publishEvent(any(RoomActivityEvent.class));
    }
}
