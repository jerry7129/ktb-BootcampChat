package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.websocket.socketio.ConnectedUsers;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.UUID;
import java.net.InetSocketAddress;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.DUPLICATE_LOGIN;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.SESSION_ENDED;

@ExtendWith(MockitoExtension.class)
class ConnectionLoginHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private ConnectedUsers connectedUsers;
    @Mock private UserRooms userRooms;
    @Mock private RoomJoinHandler roomJoinHandler;
    @Mock private SocketIOClient client;
    @Mock private ScheduledExecutorService duplicateLoginScheduler;

    private ConnectionLoginHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ConnectionLoginHandler(
                socketIOServer,
                connectedUsers,
                userRooms,
                roomJoinHandler,
                new SimpleMeterRegistry(),
                duplicateLoginScheduler);
    }

    @Test
    void onConnect_setsUserRejoinsRoomsStoresUserAndJoinsUserRooms() {
        SocketUser user = new SocketUser("user-1", "tester", "session-1", "socket-1");
        when(connectedUsers.get(user.id())).thenReturn(null);
        when(client.get("user")).thenReturn(user);
        when(userRooms.get(user.id())).thenReturn(Set.of("room-1", "room-2"));

        handler.onConnect(client, user);

        verify(client).set("user", user);
        verify(roomJoinHandler).handleJoinRoom(client, "room-1");
        verify(roomJoinHandler).handleJoinRoom(client, "room-2");
        verify(connectedUsers).set(user.id(), user);
        verify(client).joinRooms(Set.of("user:" + user.id(), "room-list", "socket:" + user.socketId()));
    }

    @Test
    void onDisconnect_removesCurrentConnectionWithoutLeavingRooms() {
        // 소켓 연결 해제만으로는 방 참가 상태(참가자 목록, 시스템 메시지)를 건드리지 않아야 한다.
        // 와이파이 순단/탭 백그라운드/배포로 인한 재연결마다 방을 나갔다 재입장하는 것을 막기 위함.
        // 진짜 나가기는 RoomLeaveHandler의 LEAVE_ROOM 이벤트로만 처리한다.
        UUID socketId = UUID.randomUUID();
        SocketUser user = new SocketUser("user-1", "tester", "session-1", socketId.toString());
        when(client.get("user")).thenReturn(user);
        when(client.getSessionId()).thenReturn(socketId);
        when(connectedUsers.get(user.id())).thenReturn(user);

        handler.onDisconnect(client);

        verify(userRooms, never()).get(user.id());
        verify(connectedUsers).del(user.id());
        verify(client).leaveRooms(Set.of("user:" + user.id(), "room-list", "socket:" + socketId));
        verify(client).del("user");
        verify(client).disconnect();
    }

    @Test
    void onConnect_notifiesAndEndsOnlyThePreviouslyConnectedSocket() {
        UUID newSocketId = UUID.randomUUID();
        SocketUser previousUser = new SocketUser("user-1", "tester", "session-old", "socket-old");
        SocketUser newUser = new SocketUser("user-1", "tester", "session-new", newSocketId.toString());
        BroadcastOperations previousSocket = org.mockito.Mockito.mock(BroadcastOperations.class);
        HandshakeData handshakeData = org.mockito.Mockito.mock(HandshakeData.class);
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.set("User-Agent", "test-agent");

        when(connectedUsers.get(newUser.id())).thenReturn(previousUser);
        when(socketIOServer.getRoomOperations("socket:socket-old")).thenReturn(previousSocket);
        when(client.getHandshakeData()).thenReturn(handshakeData);
        when(handshakeData.getHttpHeaders()).thenReturn(headers);
        when(client.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));
        when(client.get("user")).thenReturn(newUser);
        when(userRooms.get(newUser.id())).thenReturn(Set.of());

        handler.onConnect(client, newUser);

        verify(previousSocket).sendEvent(eq(DUPLICATE_LOGIN), any());
        ArgumentCaptor<Runnable> delayedEnd = ArgumentCaptor.forClass(Runnable.class);
        verify(duplicateLoginScheduler).schedule(delayedEnd.capture(), eq(10L), eq(TimeUnit.SECONDS));

        delayedEnd.getValue().run();

        verify(previousSocket).sendEvent(eq(SESSION_ENDED), any());
        verify(socketIOServer, never()).getRoomOperations("user:" + newUser.id());
    }
}
