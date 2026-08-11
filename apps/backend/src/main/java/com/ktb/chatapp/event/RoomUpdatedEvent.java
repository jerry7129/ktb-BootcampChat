package com.ktb.chatapp.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class RoomUpdatedEvent extends ApplicationEvent {
    private final String roomId;
    private final int participantsCount;

    public RoomUpdatedEvent(Object source, String roomId, int participantsCount) {
        super(source);
        this.roomId = roomId;
        this.participantsCount = participantsCount;
    }
}
