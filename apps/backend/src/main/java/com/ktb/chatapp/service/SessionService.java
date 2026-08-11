package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.service.session.SessionStore;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Service;

import static com.ktb.chatapp.model.Session.SESSION_TTL;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionStore sessionStore;
    public static final long SESSION_TTL_SEC = DurationStyle.detectAndParse(SESSION_TTL).getSeconds();
    private static final long SESSION_TIMEOUT = SESSION_TTL_SEC * 1000;

    // lastActivity와 expiresAt은 최대 30초에 한 번만 저장
    private static final long SESSION_TOUCH_INTERVAL_MS = 30_000;

    private Session buildNewSession(String userId, SessionMetadata metadata) {
        long now = Instant.now().toEpochMilli();

        return Session.builder()
                .userId(userId)
                .sessionId(generateSessionId())
                .createdAt(now)
                .lastActivity(now)
                .metadata(metadata)
                .expiresAt(Instant.ofEpochMilli(now).plusSeconds(SESSION_TTL_SEC))
                .build();
    }

    private SessionCreationResult toCreationResult(Session session) {
        return SessionCreationResult.builder()
                .sessionId(session.getSessionId())
                .expiresIn(SESSION_TTL_SEC)
                .sessionData(toSessionData(session))
                .build();
    }

    private String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private SessionData toSessionData(Session session) {
        return SessionData.builder()
                .userId(session.getUserId())
                .sessionId(session.getSessionId())
                .createdAt(session.getCreatedAt())
                .lastActivity(session.getLastActivity())
                .metadata(session.getMetadata())
                .build();
    }

    public SessionCreationResult createSession(String userId, SessionMetadata metadata) {
        try {
            Session session = buildNewSession(userId, metadata);

            // 같은 userId 키에 SET하므로 기존 세션을 원자적으로 덮어쓴다.
            Session savedSession = sessionStore.save(session);

            return toCreationResult(savedSession);
        } catch (Exception e) {
            log.error("Session creation error for userId: {}", userId, e);
            throw new RuntimeException("세션 생성 중 오류가 발생했습니다.", e);
        }
    }

    public Optional<SessionCreationResult> rotateSession(
            String userId,
            String currentSessionId,
            SessionMetadata metadata
    ) {
        try {
            if (userId == null || currentSessionId == null) {
                return Optional.empty();
            }

            Session newSession = buildNewSession(userId, metadata);

            boolean replaced = sessionStore.replaceIfMatches(userId, currentSessionId, newSession);

            if (!replaced) {
                log.debug("Session rotation rejected: userId={}, sessionId={}", userId, currentSessionId);

                return Optional.empty();
            }

            return Optional.of( toCreationResult(newSession));

        } catch (Exception e) {
            log.error("Session rotation failed for userId: {}", userId, e);

            throw new RuntimeException("세션 갱신 중 오류가 발생했습니다.", e);
        }
    }

    public SessionValidationResult validateSession(String userId, String sessionId) {
        try {
            if (userId == null || sessionId == null) {
                log.warn("validateSession called with null parameters: userId={}, sessionId={}", userId, sessionId);
                return SessionValidationResult.invalid("INVALID_PARAMETERS", "유효하지 않은 세션 파라미터");
            }

            Session session = sessionStore.findByUserId(userId).orElse(null);
            
            if (session == null) {
                log.warn("No session found for userId: {}", userId);
                return SessionValidationResult.invalid("INVALID_SESSION", "세션을 찾을 수 없습니다.");
            }

            if (!sessionId.equals(session.getSessionId())) {
                log.warn("Session ID mismatch for userId: {}. Provided: {}, Expected: {}", userId, sessionId, session.getSessionId());
                return SessionValidationResult.invalid("INVALID_SESSION", "잘못된 세션 ID입니다.");
            }

            // Check if session has timed out
            long now = Instant.now().toEpochMilli();
            if (now - session.getLastActivity() > SESSION_TIMEOUT) {
                log.warn("Session timed out for userId: {}, sessionId: {}", userId, sessionId);
                removeSession(userId, sessionId);
                return SessionValidationResult.invalid("SESSION_EXPIRED", "세션이 만료되었습니다.");
            }

            // 최근에 갱신된 세션은 DB에 다시 저장하지 않는다.
            if (now - session.getLastActivity() >= SESSION_TOUCH_INTERVAL_MS) {
                session.setLastActivity(now);
                session.setExpiresAt(
                        Instant.ofEpochMilli(now).plusSeconds(SESSION_TTL_SEC)
                );
                session = sessionStore.save(session);
            }

            SessionData sessionData = toSessionData(session);
            return SessionValidationResult.valid(sessionData);

        } catch (Exception e) {
            log.error("Session validation error for userId: {}, sessionId: {}", userId, sessionId, e);
            return SessionValidationResult.invalid("VALIDATION_ERROR", "세션 검증 중 오류가 발생했습니다.");
        }
    }

    public void updateLastActivity(String userId) {
        try {
            if (userId == null) {
                log.warn("updateLastActivity called with null userId");
                return;
            }

            Session session = sessionStore.findByUserId(userId).orElse(null);
            if (session == null) {
                log.debug("No session found to update last activity for user: {}", userId);
                return;
            }

            session.setLastActivity(Instant.now().toEpochMilli());
            session.setExpiresAt(Instant.now().plusSeconds(SESSION_TTL_SEC));
            sessionStore.save(session);
            
        } catch (Exception e) {
            log.error("Failed to update session activity for user: {}", userId, e);
        }
    }

    public void removeSession(String userId, String sessionId) {
        try {
            if (sessionId != null) {
                sessionStore.delete(userId, sessionId);
            } else {
                sessionStore.deleteAll(userId);
            }
        } catch (Exception e) {
            log.error("Session removal error for userId: {}, sessionId: {}", userId, sessionId, e);
            throw new RuntimeException("세션 삭제 중 오류가 발생했습니다.", e);
        }
    }

    public void removeSession(String userId) {
        removeSession(userId, null);
    }

    public void removeAllUserSessions(String userId) {
        try {
            sessionStore.deleteAll(userId);
        } catch (Exception e) {
            log.error("Remove all sessions error for userId: {}", userId, e);
            throw new RuntimeException("모든 세션 삭제 중 오류가 발생했습니다.", e);
        }
    }

    public SessionData getActiveSession(String userId) {
        try {
            Session session = sessionStore.findByUserId(userId).orElse(null);
            
            if (session == null) {
                return null;
            }

            return toSessionData(session);
        } catch (Exception e) {
            log.error("Get active session error for userId: {}", userId, e);
            return null;
        }
    }
}
