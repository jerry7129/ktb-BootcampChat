package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.dto.RoomResponse;
import com.ktb.chatapp.dto.RoomsResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.datafaker.Faker;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 방 목록 조회(GET /api/rooms)가 방/참가자 수가 늘어나도
 * N+1 없이 일정한 쿼리 수로 동작하는지 더미 데이터로 검증한다.
 * (실 Mongo 컨테이너의 serverStatus opcounters로 실제 왕복 횟수를 센다)
 */
@Slf4j
@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false"
})
class RoomServiceLoadIntegrationTest {

    private static final int ROOM_COUNT = 30;
    private static final int USER_POOL_SIZE = 40;
    private static final int PARTICIPANTS_PER_ROOM = 8;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    private Faker faker;

    @BeforeEach
    void setUp() {
        faker = new Faker();
    }

    @AfterEach
    void tearDown() {
        roomRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("방 30개 x 참가자 8명 더미데이터에서도 쿼리 수가 방 개수에 비례하지 않는다")
    void getAllRooms_withManyRoomsAndParticipants_doesNotIssueQueryPerRoom() {
        List<User> users = new ArrayList<>();
        for (int i = 0; i < USER_POOL_SIZE; i++) {
            users.add(userRepository.save(User.builder()
                    .name(faker.name().fullName())
                    .email(faker.internet().emailAddress())
                    .build()));
        }

        Random random = new Random(42);
        for (int i = 0; i < ROOM_COUNT; i++) {
            User creator = users.get(random.nextInt(users.size()));
            Room room = new Room();
            room.setName("room-" + i);
            room.setCreator(creator.getId());
            room.addParticipant(creator.getId());
            for (int p = 0; p < PARTICIPANTS_PER_ROOM; p++) {
                room.addParticipant(users.get(random.nextInt(users.size())).getId());
            }
            roomRepository.save(room);
        }

        long queriesBefore = currentMongoQueryCount();

        RoomsResponse response = roomService.getAllRooms(users.get(0).getEmail());

        long queriesIssued = currentMongoQueryCount() - queriesBefore;
        log.info("getAllRooms: room={}, participantsPerRoom={} -> Mongo 쿼리 {}건 발생",
                ROOM_COUNT, PARTICIPANTS_PER_ROOM, queriesIssued);

        // 목록 응답은 생성자와 참여자 수만 채우며 전체 참여자 목록은 제외한다.
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).hasSize(ROOM_COUNT);
        for (RoomResponse roomResponse : response.getData()) {
            assertThat(roomResponse.getCreator()).isNotNull();
            assertThat(roomResponse.getCreator().getName()).isNotBlank();
            assertThat(roomResponse.getParticipants()).isNull();
            assertThat(roomResponse.getParticipantsCount()).isPositive();
        }

        // 생성자만 배치 조회하므로 Mongo 쿼리는 방 개수와 무관해야 한다.
        assertThat(queriesIssued)
                .as("방 개수(%d)에 비례하지 않는 적은 쿼리 수여야 함", ROOM_COUNT)
                .isLessThan(ROOM_COUNT);
    }

    private long currentMongoQueryCount() {
        Document status = mongoTemplate.getDb().runCommand(new Document("serverStatus", 1));
        Document opcounters = status.get("opcounters", Document.class);
        return opcounters.get("query", Number.class).longValue()
                + opcounters.get("command", Number.class).longValue();
    }
}
