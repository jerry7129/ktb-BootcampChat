package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.dto.RoomResponse;
import com.ktb.chatapp.event.RoomUpdatedEvent;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private RoomRepository roomRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RecentMessageCounter recentMessageCounter;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private RoomService roomService;
    private Room room;
    private User creator;
    private User joiner;

    @BeforeEach
    void setUp() {
        roomService = new RoomService(
            roomRepository, userRepository, recentMessageCounter, passwordEncoder, eventPublisher);

        creator = User.builder().id("creator-1").name("creator").email("creator@test.com").build();
        joiner = User.builder().id("user-1").name("joiner").email("joiner@test.com").build();
        room = Room.builder()
            .id("room-1")
            .name("test room")
            .creator(creator.getId())
            .participantIds(new HashSet<>(Set.of(creator.getId())))
            .build();
    }

    @Test
    void joinRoom_addsParticipantAtomicallyAndBuildsResponseOnce() {
        when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
        when(userRepository.findByEmail(joiner.getEmail())).thenReturn(Optional.of(joiner));
        when(userRepository.findAllById(Set.of(creator.getId(), joiner.getId())))
            .thenReturn(List.of(creator, joiner));
        when(recentMessageCounter.countRecentMessages("room-1")).thenReturn(3);

        RoomResponse response = roomService.joinRoom("room-1", null, joiner.getEmail());

        assertThat(response.getParticipants()).extracting("id")
            .containsExactlyInAnyOrder(creator.getId(), joiner.getId());
        assertThat(response.getRecentMessageCount()).isEqualTo(3);
        verify(roomRepository).addParticipant("room-1", joiner.getId());
        verify(roomRepository, never()).save(room);
        verify(userRepository).findAllById(Set.of(creator.getId(), joiner.getId()));
        verify(recentMessageCounter).countRecentMessages("room-1");
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(RoomUpdatedEvent.class));
    }

    @Test
    void joinRoom_doesNotWriteParticipantAgainWhenAlreadyJoined() {
        room.addParticipant(joiner.getId());
        when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
        when(userRepository.findByEmail(joiner.getEmail())).thenReturn(Optional.of(joiner));
        when(userRepository.findAllById(Set.of(creator.getId(), joiner.getId())))
            .thenReturn(List.of(creator, joiner));

        roomService.joinRoom("room-1", null, joiner.getEmail());

        verify(roomRepository, never()).addParticipant("room-1", joiner.getId());
        verify(roomRepository, never()).save(room);
    }

    @Test
    void joinRoom_rejectsWrongPasswordBeforeParticipantUpdate() {
        room.setHasPassword(true);
        room.setPassword("encoded-password");
        when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
        when(userRepository.findByEmail(joiner.getEmail())).thenReturn(Optional.of(joiner));
        when(passwordEncoder.matches("wrong", "encoded-password")).thenReturn(false);

        assertThatThrownBy(() -> roomService.joinRoom("room-1", "wrong", joiner.getEmail()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("비밀번호");

        verify(roomRepository, never()).addParticipant("room-1", joiner.getId());
        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }
}
