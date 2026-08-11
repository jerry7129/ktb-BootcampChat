package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * 메시지 읽음 상태 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReadStatusService {

    private final MongoTemplate mongoTemplate;

    /**
     * 메시지 읽음 상태 업데이트
     *
     * 메시지마다 findById+save를 따로 하면(과거 구현) 30개 배치 로드 시 Mongo 왕복이 60번
     * 발생한다. 하나의 벌크 업데이트로 "이 유저가 아직 읽지 않은 메시지"에만 원자적으로
     * reader를 추가한다 — 왕복 1번, 이미 읽은 메시지는 필터에서 자동으로 제외된다.
     *
     * @param messageIds 읽음 상태를 업데이트할 메시지 리스트
     * @param userId 읽은 사용자 ID
     */
    public void updateReadStatus(List<String> messageIds, String userId) {
        if (messageIds.isEmpty()) {
            return;
        }

        var readerInfo = Message.MessageReader.builder()
                .userId(userId)
                .readAt(LocalDateTime.now())
                .build();

        try {
            Query query = Query.query(Criteria.where("id").in(messageIds)
                    .and("readers.userId").ne(userId));
            Update update = new Update().push("readers", readerInfo);
            var result = mongoTemplate.updateMulti(query, update, Message.class);

            log.debug("Read status updated for {} of {} messages by user {}",
                    result.getModifiedCount(), messageIds.size(), userId);
        } catch (Exception e) {
            log.error("Read status update error for user {}", userId, e);
        }
    }
}
