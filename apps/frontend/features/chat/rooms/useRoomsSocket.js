import { useRef, useEffect } from 'react';
import socketClient from '@/lib/socket/socketClient';

const CONNECTION_STATUS = {
  CONNECTED: 'connected',
  DISCONNECTED: 'disconnected',
  ERROR: 'error',
};

export const useRoomsSocket = ({
  currentUser,
  setConnectionStatus,
  setRooms,
}) => {
  const socketRef = useRef(null);

  useEffect(() => {
    if (!currentUser?.token) return;

    let isSubscribed = true;
    let subscribedSocket = null;
    let subscribedHandlers = null;

    const connectSocket = async () => {
      try {
        const socket = await socketClient
          .connect({
            auth: {
              token: currentUser.token,
              sessionId: currentUser.sessionId,
            },
          })
          .catch((err) => {
            console.log('Socket connection error:', err);
            setConnectionStatus(CONNECTION_STATUS.ERROR);
          });

        if (!isSubscribed || !socket) return;

        socketRef.current = socket;
        subscribedSocket = socket;

        const handlers = {
          connect: () => {
            setConnectionStatus(CONNECTION_STATUS.CONNECTED);
          },
          disconnect: () => {
            setConnectionStatus(CONNECTION_STATUS.DISCONNECTED);
          },
          error: () => {
            setConnectionStatus(CONNECTION_STATUS.ERROR);
          },
          roomCreated: (newRoom) => {
            setRooms((prev) => [newRoom, ...prev]);
          },
          roomUpdated: (updatedRoom) => {
            if (!updatedRoom?._id) return;

            setRooms((prev) =>
              prev.map((room) =>
                room._id === updatedRoom._id
                  ? { ...room, participantsCount: updatedRoom.participantsCount }
                  : room
              )
            );
          },
          // 활성도 지표만 담긴 경량 payload이므로 방 정보를 덮지 않고 병합한다
          roomActivity: (activity) => {
            if (!activity?._id) return;

            setRooms((prev) =>
              prev.map((room) =>
                room._id === activity._id
                  ? { ...room, recentMessageCount: activity.recentMessageCount }
                  : room
              )
            );
          },
        };

        Object.entries(handlers).forEach(([event, handler]) => {
          socket.on(event, handler);
        });
        subscribedHandlers = handlers;

        // 공유 소켓을 재사용하면 connect 이벤트는 이미 발생한 뒤다. 이벤트만
        // 기다리면 목록 화면이 checking 상태에 머물 수 있으므로 즉시 동기화한다.
        if (socket.connected) {
          setConnectionStatus(CONNECTION_STATUS.CONNECTED);
        }
      } catch (error) {
        if (!isSubscribed) return;

        if (
          error.message?.includes('Authentication required') ||
          error.message?.includes('Invalid session')
        ) {
          // Auth error will be handled by the useAuth context
        }

        setConnectionStatus(CONNECTION_STATUS.ERROR);
      }
    };

    connectSocket();

    return () => {
      isSubscribed = false;

      if (subscribedSocket && subscribedHandlers) {
        Object.entries(subscribedHandlers).forEach(([event, handler]) => {
          subscribedSocket.off(event, handler);
        });
      }

      // SocketService가 인증 세션 동안 연결을 소유한다. 목록 화면을 벗어날 때
      // 연결까지 끊으면 방 상세 화면이 매번 새 handshake를 해야 한다.
      socketRef.current = null;
    };
  }, [currentUser]); // eslint-disable-line react-hooks/exhaustive-deps

  return { socketRef };
};

export default useRoomsSocket;
