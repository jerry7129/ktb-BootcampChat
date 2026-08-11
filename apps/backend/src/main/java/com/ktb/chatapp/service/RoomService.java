package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.*;
import com.ktb.chatapp.event.RoomCreatedEvent;
import com.ktb.chatapp.event.RoomUpdatedEvent;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Page;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final RecentMessageCounter recentMessageCounter;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    // 부하 테스트가 반복될수록 방 데이터가 계속 쌓이는데, findAll()로 전체를 매번 읽어와
    // 정렬하면 방 개수에 비례해 이 호출이 느려진다. 목록 화면은 어차피 최신 방 위주로
    // 보여주면 충분하므로 최신순 상한을 두고 그 이상은 DB에 정렬을 맡긴다(인덱스 없이도
    // limit 덕분에 전체 문서를 메모리에 올리지 않는다). 방 생성/조회/입장 API와 응답
    // 필드는 그대로이므로 랜덤 입장 버튼을 쓰는 E2E 시나리오와도 호환된다.
    @Value("${chatapp.room-list.max-size:200}")
    private int roomListMaxSize;

    public RoomsResponse getAllRooms(String name) {

        try {
            Page<Room> roomPage = roomRepository.findAll(
                PageRequest.of(0, roomListMaxSize, Sort.by(Sort.Direction.DESC, "createdAt")));
            List<Room> rooms = roomPage.getContent();

            // 방마다 참가자/생성자를 개별 조회(N+1)하지 않도록 전체 유저 ID를 모아 한 번에 조회한다
            Set<String> userIds = new HashSet<>();
            List<String> roomIds = new java.util.ArrayList<>(rooms.size());
            for (Room room : rooms) {
                roomIds.add(room.getId());
                if (room.getCreator() != null) {
                    userIds.add(room.getCreator());
                }
                userIds.addAll(room.getParticipantIds());
            }
            Map<String, User> usersById = new HashMap<>();
            if (!userIds.isEmpty()) {
                userRepository.findAllById(userIds)
                    .forEach(user -> usersById.put(user.getId(), user));
            }

            // 방마다 최근 메시지 수를 개별 조회(N+1)하지 않도록 집계 쿼리 한 번으로 가져온다
            Map<String, Integer> recentMessageCounts = recentMessageCounter.countRecentMessages(roomIds);

            List<RoomResponse> roomResponses = rooms.stream()
                .map(room -> mapToRoomResponse(room, name, usersById,
                    recentMessageCounts.getOrDefault(room.getId(), 0)))
                .sorted(Comparator.comparing(
                    RoomResponse::getCreatedAtDateTime,
                    Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());

            long total = roomPage.getTotalElements();
            PageMetadata metadata = PageMetadata.builder()
                .total(total)
                .page(0)
                .pageSize(roomListMaxSize)
                .totalPages(roomPage.getTotalPages())
                .hasMore(total > roomResponses.size())
                .currentCount(roomResponses.size())
                .build();

            return RoomsResponse.builder()
                .success(true)
                .data(roomResponses)
                .metadata(metadata)
                .build();

        } catch (Exception e) {
            log.error("방 목록 조회 에러", e);
            return RoomsResponse.builder()
                .success(false)
                .data(List.of())
                .build();
        }
    }

    public HealthResponse getHealthStatus() {
        try {
            long startTime = System.currentTimeMillis();

            // MongoDB 연결 상태 확인
            boolean isMongoConnected = false;
            long latency = 0;

            try {
                // 간단한 쿼리로 연결 상태 및 지연 시간 측정
                roomRepository.findOneForHealthCheck();
                long endTime = System.currentTimeMillis();
                latency = endTime - startTime;
                isMongoConnected = true;
            } catch (Exception e) {
                log.warn("MongoDB 연결 확인 실패", e);
                isMongoConnected = false;
            }

            // 최근 활동 조회
            LocalDateTime lastActivity = roomRepository.findMostRecentRoom()
                    .map(Room::getCreatedAt)
                    .orElse(null);

            // 서비스 상태 정보 구성
            Map<String, HealthResponse.ServiceHealth> services = new HashMap<>();
            services.put("database", HealthResponse.ServiceHealth.builder()
                .connected(isMongoConnected)
                .latency(latency)
                .build());

            return HealthResponse.builder()
                .success(true)
                .services(services)
                .lastActivity(lastActivity)
                .build();

        } catch (Exception e) {
            log.error("Health check 실행 중 에러 발생", e);
            return HealthResponse.builder()
                .success(false)
                .services(new HashMap<>())
                .build();
        }
    }

    public Room createRoom(CreateRoomRequest createRoomRequest, String name) {
        User creator = userRepository.findByEmail(name)
            .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        Room room = new Room();
        room.setName(createRoomRequest.getName().trim());
        room.setCreator(creator.getId());
        room.getParticipantIds().add(creator.getId());

        if (createRoomRequest.getPassword() != null && !createRoomRequest.getPassword().isEmpty()) {
            room.setHasPassword(true);
            room.setPassword(passwordEncoder.encode(createRoomRequest.getPassword()));
        }

        Room savedRoom = roomRepository.save(room);
        
        // Publish event for room created
        try {
            RoomResponse roomResponse = mapToRoomResponse(savedRoom, name);
            eventPublisher.publishEvent(new RoomCreatedEvent(this, roomResponse));
        } catch (Exception e) {
            log.error("roomCreated 이벤트 발행 실패", e);
        }
        
        return savedRoom;
    }

    public Optional<Room> findRoomById(String roomId) {
        return roomRepository.findById(roomId);
    }

    public RoomResponse joinRoom(
            String roomId,
            String password,
            String userId
    ) {
        Room room = roomRepository.findById(roomId).orElse(null);

        if (room == null) {
            return null;
        }

        // 참가자 추가 전에 비밀번호를 먼저 검증해야 한다.
        if (room.isHasPassword()) {
            if (password == null
                    || !passwordEncoder.matches(password, room.getPassword())) {
                throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
            }
        }

        // JWT에서 받은 userId를 직접 사용한다.
        if (!room.getParticipantIds().contains(userId)) {
            // 방 문서 전체를 저장하지 않고 참가자 한 명만 원자적으로 추가한다.
            roomRepository.addParticipant(roomId, userId);
            room.addParticipant(userId);
        }

        RoomResponse roomResponse =
                mapToRoomResponse(room, userId);

        try {
            eventPublisher.publishEvent(
                    new RoomUpdatedEvent(
                            this,
                            roomId,
                            roomResponse
                    )
            );
        } catch (Exception e) {
            log.error(
                    "roomUpdate 이벤트 발행 실패: roomId={}",
                    roomId,
                    e
            );
        }

        return roomResponse;
    }

    private RoomResponse mapToRoomResponse(Room room, String name) {
        if (room == null) return null;

        Set<String> userIds = new HashSet<>(room.getParticipantIds());
        if (room.getCreator() != null) {
            userIds.add(room.getCreator());
        }
        Map<String, User> usersById = userRepository.findAllById(userIds).stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
        int recentMessageCount = recentMessageCounter.countRecentMessages(room.getId());

        return mapToRoomResponse(room, name, usersById, recentMessageCount);
    }

    private RoomResponse mapToRoomResponse(
            Room room, String name, Map<String, User> usersById, int recentMessageCount) {
        if (room == null) return null;

        User creator = room.getCreator() != null ? usersById.get(room.getCreator()) : null;

        List<User> participants = room.getParticipantIds().stream()
            .map(usersById::get)
            .filter(Objects::nonNull)
            .toList();

        return RoomResponse.builder()
            .id(room.getId())
            .name(room.getName() != null ? room.getName() : "제목 없음")
            .hasPassword(room.isHasPassword())
            .creator(creator != null ? UserResponse.builder()
                .id(creator.getId())
                .name(creator.getName() != null ? creator.getName() : "알 수 없음")
                .email(creator.getEmail() != null ? creator.getEmail() : "")
                .build() : null)
            .participants(participants.stream()
                .filter(p -> p != null && p.getId() != null)
                .map(p -> UserResponse.builder()
                    .id(p.getId())
                    .name(p.getName() != null ? p.getName() : "알 수 없음")
                    .email(p.getEmail() != null ? p.getEmail() : "")
                    .build())
                .collect(Collectors.toList()))
            .createdAtDateTime(room.getCreatedAt())
            .isCreator(creator != null && creator.getId().equals(name))
            .recentMessageCount(recentMessageCount)
            .build();
    }
}
