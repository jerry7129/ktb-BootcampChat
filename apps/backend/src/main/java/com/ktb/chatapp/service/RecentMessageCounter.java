package com.ktb.chatapp.service;

import com.ktb.chatapp.repository.MessageRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * 채팅방 목록에 노출하는 "최근 메시지 수"의 집계 창을 한곳에서 관리한다.
 */
@Component
@RequiredArgsConstructor
public class RecentMessageCounter {

    static final Duration RECENT_WINDOW = Duration.ofMinutes(30);

    private final MessageRepository messageRepository;
    private final MongoTemplate mongoTemplate;

    public int countRecentMessages(String roomId) {
        LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);
        return (int) messageRepository.countRecentMessagesByRoomId(roomId, since);
    }

    /**
     * 여러 방의 최근 메시지 수를 집계 쿼리 한 번으로 조회한다.
     * 방 목록 조회처럼 방마다 카운트가 필요한 경우, 방 개수만큼 쿼리를 날리는 N+1을 막기 위해 사용한다.
     */
    public Map<String, Integer> countRecentMessages(List<String> roomIds) {
        if (roomIds.isEmpty()) {
            return Map.of();
        }
        LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);

        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.match(Criteria.where("room").in(roomIds).and("timestamp").gte(since)),
            Aggregation.group("room").count().as("count")
        );

        AggregationResults<Document> results =
            mongoTemplate.aggregate(aggregation, "messages", Document.class);

        Map<String, Integer> counts = new HashMap<>();
        for (Document doc : results.getMappedResults()) {
            counts.put(doc.getString("_id"), doc.getInteger("count"));
        }
        return counts;
    }
}
