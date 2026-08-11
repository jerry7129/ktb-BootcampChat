package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.mongodb.ExplainVerbosity;
import com.mongodb.client.MongoCollection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.test.context.TestPropertySource;

/**
 * 수동 실험용: Message 컴파운드 인덱스({room:1, timestamp:-1})가 실제로
 * COLLSCAN -> IXSCAN 전환과 docsExamined 감소를 만드는지 대량 데이터로 확인한다.
 * <p>
 * 기본 {@code mvn test}에는 포함되지 않는다 (surefire 기본 패턴은 *Test.java인데
 * 이 클래스는 *Experiment.java라 안 걸림). 아래처럼 명시적으로만 실행:
 * <pre>{@code ./mvnw test -Dtest=MessageIndexExplainExperiment}</pre>
 * 방 50개 x 2,000건 = 총 10만 건 삽입 때문에 몇 분 걸릴 수 있다.
 */
@Slf4j
@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false"
})
class MessageIndexExplainExperiment {

    private static final int ROOM_COUNT = 50;
    private static final int MESSAGES_PER_ROOM = 2000;
    private static final int BATCH_SIZE = 5000;
    private static final String INDEX_NAME = "room_timestamp_idx";

    @Autowired
    private MongoTemplate mongoTemplate;

    private String targetRoomId;

    @AfterEach
    void tearDown() {
        mongoTemplate.dropCollection("messages");
    }

    @Test
    void explainShowsIndexScanInsteadOfCollectionScan() {
        seedMessages();
        LocalDateTime cursor = LocalDateTime.now().plusMinutes(1);

        // 1) 인덱스 없이 실행 -> 콜렉션 전체를 훑는지 확인
        dropIndexIfExists();
        ExplainResult before = ExplainResult.from(explainFindByRoomAndTimestamp(targetRoomId, cursor));
        log.info("[인덱스 없음] stage={}, docsExamined={}, nReturned={}",
                before.stage(), before.docsExamined(), before.nReturned());

        // 2) 인덱스 생성 후 재실행 -> 필요한 문서만 훑는지 확인
        createCompoundIndex();
        ExplainResult after = ExplainResult.from(explainFindByRoomAndTimestamp(targetRoomId, cursor));
        log.info("[인덱스 있음] stage={}, docsExamined={}, nReturned={}",
                after.stage(), after.docsExamined(), after.nReturned());

        int totalMessages = ROOM_COUNT * MESSAGES_PER_ROOM;
        log.info("=== 요약: 총 메시지 {}건 중, 인덱스 없이 {}건 훑음 -> 인덱스 있으면 {}건만 훑음 ===",
                totalMessages, before.docsExamined(), after.docsExamined());

        // 인덱스 없이 room+timestamp로 정렬까지 해야 하니 SORT->COLLSCAN(정렬은 메모리에서, 훑기는 전체) 형태로 나온다
        assertThat(before.stage()).contains("COLLSCAN");
        assertThat(before.docsExamined()).isEqualTo(totalMessages);

        assertThat(after.stage()).contains("IXSCAN");
        assertThat(after.docsExamined())
                .as("인덱스가 있으면 반환 건수 근처만 훑어야 한다 (전체 %d건이 아니라)", totalMessages)
                .isLessThan(before.docsExamined() / 10);
    }

    private void seedMessages() {
        Random random = new Random(7);
        LocalDateTime base = LocalDateTime.now().minusDays(3);
        int windowSeconds = 3 * 24 * 3600;

        List<Message> batch = new ArrayList<>(BATCH_SIZE);
        int total = 0;
        for (int roomIndex = 0; roomIndex < ROOM_COUNT; roomIndex++) {
            String roomId = "room-" + roomIndex;
            if (roomIndex == 0) {
                targetRoomId = roomId;
            }
            for (int i = 0; i < MESSAGES_PER_ROOM; i++) {
                Message message = new Message();
                message.setRoomId(roomId);
                message.setSenderId("seed-user");
                message.setContent("dummy message " + i);
                message.setType(MessageType.text);
                message.setTimestamp(base.plusSeconds(random.nextInt(windowSeconds)));
                batch.add(message);
                total++;
                if (batch.size() == BATCH_SIZE) {
                    mongoTemplate.insert(batch, Message.class);
                    batch.clear();
                    log.info("메시지 {}건 삽입됨", total);
                }
            }
        }
        if (!batch.isEmpty()) {
            mongoTemplate.insert(batch, Message.class);
        }
        log.info("총 {}건 메시지 삽입 완료 (방 {}개 x {}건)", total, ROOM_COUNT, MESSAGES_PER_ROOM);
    }

    private void dropIndexIfExists() {
        try {
            mongoTemplate.indexOps(Message.class).dropIndex(INDEX_NAME);
            log.info("인덱스 {} 삭제함", INDEX_NAME);
        } catch (Exception e) {
            log.info("인덱스 {}가 이미 없음(정상): {}", INDEX_NAME, e.getMessage());
        }
    }

    private void createCompoundIndex() {
        mongoTemplate.indexOps(Message.class).createIndex(
                new Index()
                        .on("room", Sort.Direction.ASC)
                        .on("timestamp", Sort.Direction.DESC)
                        .named(INDEX_NAME));
        log.info("인덱스 {} 생성함", INDEX_NAME);
    }

    private Document explainFindByRoomAndTimestamp(String roomId, LocalDateTime before) {
        MongoCollection<Document> collection = mongoTemplate.getCollection("messages");
        Document filter = new Document("room", roomId)
                .append("timestamp", new Document("$lt", before));
        return collection.find(filter)
                .sort(new Document("timestamp", -1))
                .limit(30)
                .explain(ExplainVerbosity.EXECUTION_STATS);
    }

    private record ExplainResult(String stage, int docsExamined, int nReturned) {
        static ExplainResult from(Document explain) {
            Document executionStats = explain.get("executionStats", Document.class);
            Document winningPlan = explain.get("queryPlanner", Document.class)
                    .get("winningPlan", Document.class);
            return new ExplainResult(
                    extractStagePath(winningPlan),
                    executionStats.getInteger("totalDocsExamined"),
                    executionStats.getInteger("nReturned"));
        }

        private static String extractStagePath(Document plan) {
            StringBuilder path = new StringBuilder();
            Document current = plan;
            while (current != null) {
                if (!path.isEmpty()) {
                    path.append("->");
                }
                path.append(current.getString("stage"));
                current = current.get("inputStage", Document.class);
            }
            return path.toString();
        }
    }
}
