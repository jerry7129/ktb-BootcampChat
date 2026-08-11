package com.ktb.chatapp.dto;

public record ParticipantUpdateResponse(
        String type,
        UserResponse participant,
        String userId,
        int participantsCount
) {
    public static ParticipantUpdateResponse joined(
            UserResponse participant, int participantsCount) {
        return new ParticipantUpdateResponse(
                "joined", participant, participant.getId(), participantsCount);
    }

    public static ParticipantUpdateResponse left(String userId, int participantsCount) {
        return new ParticipantUpdateResponse("left", null, userId, participantsCount);
    }
}
