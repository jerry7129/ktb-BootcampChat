package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import static java.util.Collections.emptyList;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageLoader {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final FileRepository fileRepository;
    private final MessageResponseMapper messageResponseMapper;
    private final MessageReadStatusService messageReadStatusService;

    private static final int BATCH_SIZE = 30;

    /**
     * 메시지 로드
     */
    public FetchMessagesResponse loadMessages(FetchMessagesRequest data, String userId) {
        try {
            return loadMessagesInternal(data.roomId(), data.limit(BATCH_SIZE), data.before(LocalDateTime.now()), userId);
        } catch (Exception e) {
            log.error("Error loading initial messages for room {}", data.roomId(), e);
            return FetchMessagesResponse.builder()
                    .messages(emptyList())
                    .hasMore(false)
                    .build();
        }
    }

    private FetchMessagesResponse loadMessagesInternal(
            String roomId,
            int limit,
            LocalDateTime before,
            String userId) {
        Pageable pageable = PageRequest.of(0, limit, Sort.by("timestamp").descending());

        Page<Message> messagePage = messageRepository
                .findByRoomIdAndTimestampBefore(roomId, before, pageable);

        List<Message> messages = messagePage.getContent();

        // DESC로 조회했으므로 ASC로 재정렬 (채팅 UI 표시 순서)
        List<Message> sortedMessages = messages.reversed();
        
        var messageIds = sortedMessages.stream().map(Message::getId).toList();
        messageReadStatusService.updateReadStatus(messageIds, userId);

        // 발신자/파일을 메시지마다 개별 조회(N+1)하지 않도록 배치로 한 번에 조회한다
        Set<String> senderIds = sortedMessages.stream()
                .map(Message::getSenderId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        // Map.of()는 get(null)에서 NPE를 던지므로(메시지 대부분은 senderId/fileId가 null일 수 있음)
        // 빈 경우에도 null-key 조회가 안전한 HashMap을 사용한다.
        Map<String, User> usersById = new HashMap<>();
        if (!senderIds.isEmpty()) {
            userRepository.findAllById(senderIds)
                    .forEach(user -> usersById.put(user.getId(), user));
        }

        Set<String> fileIds = sortedMessages.stream()
                .map(Message::getFileId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, File> filesById = new HashMap<>();
        if (!fileIds.isEmpty()) {
            fileRepository.findAllById(fileIds)
                    .forEach(file -> filesById.put(file.getId(), file));
        }

        // 메시지 응답 생성
        List<MessageResponse> messageResponses = sortedMessages.stream()
                .map(message -> messageResponseMapper.mapToMessageResponse(
                        message,
                        usersById.get(message.getSenderId()),
                        filesById.get(message.getFileId())))
                .collect(Collectors.toList());

        boolean hasMore = messagePage.hasNext();

        log.debug("Messages loaded - roomId: {}, limit: {}, count: {}, hasMore: {}",
                roomId, limit, messageResponses.size(), hasMore);

        return FetchMessagesResponse.builder()
                .messages(messageResponses)
                .hasMore(hasMore)
                .build();
    }
}
