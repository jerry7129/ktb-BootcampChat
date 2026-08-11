package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.ktb.chatapp.websocket.socketio.ConnectedUsers;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * Socket.IO Chat Handler
 * 어노테이션 기반 이벤트 처리와 인증 흐름을 정의한다.
 * 연결/해제 및 중복 로그인 처리를 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class ConnectionLoginHandler {

    private final SocketIOServer socketIOServer;
    private final ConnectedUsers connectedUsers;
    private final UserRooms userRooms;
    private final RoomJoinHandler roomJoinHandler;

    public ConnectionLoginHandler(
            SocketIOServer socketIOServer,
            ConnectedUsers connectedUsers,
            UserRooms userRooms,
            RoomJoinHandler roomJoinHandler,
            MeterRegistry meterRegistry) {
        this.socketIOServer = socketIOServer;
        this.connectedUsers = connectedUsers;
        this.userRooms = userRooms;
        this.roomJoinHandler = roomJoinHandler;

        // Register gauge metric for concurrent users
        Gauge.builder("socketio.concurrent.users", connectedUsers::size)
                .description("Current number of concurrent Socket.IO users")
                .register(meterRegistry);
    }
    
    /**
     * auth 처리가 선행되어야 해서 @OnConnect 대신 별도 메서드로 구현
     */
    public void onConnect(SocketIOClient client, SocketUser user) {
        String userId = user.id();
        
        try {
            // 다른 노드에 접속된 사용자는 통보 불가
            notifyDuplicateLogin(client, userId);
            client.set("user", user);
            
            userRooms.get(userId).forEach(roomId -> {
                // 재접속 시 기존 참여 방 재입장 처리
                roomJoinHandler.handleJoinRoom(client, roomId);
            });
            
            connectedUsers.set(userId, user);

            // 동시 접속자 수는 Prometheus gauge(socketio.concurrent.users)로 이미 노출 중이라
            // 여기서는 재조회하지 않는다. connectedUsers.size()는 Redis KEYS 스캔이라
            // 연결마다 부르면 접속자가 늘수록 접속 자체가 느려진다.
            log.info("Socket.IO user connected: {} ({})", getUserName(client), userId);

            client.joinRooms(Set.of("user:" + userId, "room-list"));
            
        } catch (Exception e) {
            log.error("Error handling Socket.IO connection", e);
            client.sendEvent(ERROR, Map.of(
                    "message", "연결 처리 중 오류가 발생했습니다."
            ));
        }
    }
    
    /**
     * 소켓 연결 해제는 "방을 나갔다"는 뜻이 아니다 (와이파이 순단, 탭 백그라운드, ping 타임아웃 등으로도
     * 발생함). 여기서 방마다 자동으로 handleLeaveRoom을 돌리면, 끊겼다 다시 붙을 때마다 참가자 목록에서
     * 빠졌다가 재입장하며 시스템 메시지+브로드캐스트가 반복된다 — 특히 배포로 서버가 재시작돼 모든 클라이언트가
     * 한꺼번에 끊겼다 재연결할 때 이 부담이 폭발적으로 늘어난다. 진짜 "나가기"는 LEAVE_ROOM 이벤트
     * (RoomLeaveHandler, beforeunload에서 명시적으로 호출됨)로만 처리한다.
     */
    @OnDisconnect
    public void onDisconnect(SocketIOClient client) {
        String userId = getUserId(client);
        String userName = getUserName(client);

        try {
            if (userId == null) {
                return;
            }

            String socketId = client.getSessionId().toString();
            
            // 해당 사용자의 현재 활성 연결인 경우에만 정리
            var socketUser = connectedUsers.get(userId);
            if (socketUser != null && socketId.equals(socketUser.socketId())) {
                connectedUsers.del(userId);
            } else {
                log.warn("Socket.IO disconnect: User {} has a different active connection. Skipping cleanup.", userId);
            }

            client.leaveRooms(Set.of("user:" + userId, "room-list"));
            client.del("user");
            client.disconnect();

            log.info("Socket.IO user disconnected: {} ({})", userName, userId);
        } catch (Exception e) {
            log.error("Error handling Socket.IO disconnection", e);
            client.sendEvent(ERROR, Map.of(
                "message", "연결 종료 처리 중 오류가 발생했습니다."
            ));
        }
        
    }
    
    private SocketUser getUserDto(SocketIOClient client) {
        return client.get("user");
    }
    
    private String getUserId(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.id() : null;
    }
    
    private String getUserName(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.name() : null;
    }
    
    /**
     * "user:{userId}" 룸으로 브로드캐스트한다 (RedissonStoreFactory 덕분에
     * 룸 멤버십이 인스턴스 간에 공유돼서 멀티 클러스터에서도 동작함).
     * 이전에는 socketIOServer.getClient(UUID)로 현재 JVM에 붙은 소켓만
     * 찾았는데, 그러면 (1) 다른 노드에 붙은 예전 세션은 못 찾고
     * (2) Redis 조회 지연 때문에 방금 끊긴 연결을 아직 살아있다고
     * 착각하는 레이스가 생겼다. 방 브로드캐스트는 방금 연결한 client만
     * 제외하고 보내므로, 예전 연결이 이미 나갔으면 그냥 아무도 못 받고
     * 끝난다 (안전), 실제로 살아있으면 어느 노드에 있든 정확히 받는다.
     */
    private void notifyDuplicateLogin(SocketIOClient client, String userId) {
        var socketUser = connectedUsers.get(userId);
        if (socketUser == null) {
            return;
        }

        var userRoom = socketIOServer.getRoomOperations("user:" + userId);
        // User-Agent가 없는 클라이언트(브라우저가 아닌 부하테스트 도구 등)도 있다.
        // Map.of()는 값이 null이면 그 자리에서 NPE를 던지므로 기본값으로 채운다.
        String deviceInfo = Objects.requireNonNullElse(
                client.getHandshakeData().getHttpHeaders().get("User-Agent"), "unknown");

        // Send duplicate login notification
        userRoom.sendEvent(DUPLICATE_LOGIN, client, Map.of(
                "type", "new_login_attempt",
                "deviceInfo", deviceInfo,
                "ipAddress", client.getRemoteAddress().toString(),
                "timestamp", System.currentTimeMillis()
        ));

        new Thread(() -> {
            try {
                Thread.sleep(Duration.ofSeconds(10));
                userRoom.sendEvent(SESSION_ENDED, client, Map.of(
                        "reason", "duplicate_login",
                        "message", "다른 기기에서 로그인하여 현재 세션이 종료되었습니다."
                ));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Error in duplicate login notification thread", e);
            }
        }).start();
    }
}
