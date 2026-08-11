package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * MongoDB implementation of SessionStore.
 */
@Component
@RequiredArgsConstructor
public class SessionMongoStore implements SessionStore {

    private final MongoOperations mongoOperations;

    /**
     * userId 기준으로 현재 세션을 조회한다.
     */
    @Override
    public Optional<Session> findByUserId(String userId) {
        Query query = Query.query(Criteria.where("userId").is(userId));

        Session session = mongoOperations.findOne(query, Session.class);

        return Optional.ofNullable(session);
    }

    /**
     * userId당 하나의 세션만 유지한다.
     *
     * 기존 세션이 있으면 갱신하고,
     * 없으면 새 문서를 생성한다.
     */
    @Override
    public Session save(Session session) {
        Query query = Query.query(Criteria.where("userId").is(session.getUserId()));

        Update update = toUpdate(session);

        return mongoOperations.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().upsert(true).returnNew(true),
                Session.class
        );
    }

    @Override
    public boolean replaceIfMatches(String userId, String expectedSessionId, Session replacement) {
        Query query = Query.query(Criteria.where("userId").is(userId).and("sessionId").is(expectedSessionId));

        Update update = toUpdate(replacement);

        return mongoOperations
                .updateFirst(query, update, Session.class)
                .getModifiedCount() == 1;
    }

    /**
     * 현재 저장된 sessionId가 요청 sessionId와
     * 일치할 때만 세션을 삭제한다.
     */
    @Override
    public void delete(String userId, String sessionId) {
        if (userId == null || sessionId == null) {
            return;
        }

        Query query = Query.query(Criteria.where("userId").is(userId).and("sessionId").is(sessionId));

        mongoOperations.remove(query, Session.class);
    }

    /**
     * sessionId와 관계없이 사용자의 세션을 전부 삭제한다.
     *
     * 계정 탈퇴, 관리자 강제 로그아웃,
     * 테스트 정리 등에 사용한다.
     */
    @Override
    public void deleteAll(String userId) {
        if (userId == null) {
            return;
        }

        Query query = Query.query(Criteria.where("userId").is(userId));

        mongoOperations.remove(query, Session.class);
    }

    private Update toUpdate(Session session) {
        return new Update()
                .set("userId", session.getUserId())
                .set("sessionId", session.getSessionId())
                .set("createdAt", session.getCreatedAt())
                .set("lastActivity", session.getLastActivity())
                .set("metadata", session.getMetadata())
                .set("expiresAt", session.getExpiresAt());
    }
}
