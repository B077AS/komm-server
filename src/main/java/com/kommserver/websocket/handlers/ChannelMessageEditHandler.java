package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.Channel;
import com.kommserver.model.db.Message;
import com.kommserver.repository.ChannelRepository;
import com.kommserver.service.MessageService;
import com.kommserver.websocket.senders.ClientMessageSender;
import com.kommserver.websocket.managers.ClientSessionManager;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.ChannelMessageEditedPayload;
import com.kommserver.websocket.messages.payloads.MessageEditPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelMessageEditHandler implements ClientInboundMessageHandler {

    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_CODE_MESSAGE_LENGTH = 50000;

    private final Gson gson;
    private final MessageService messageService;
    private final ChannelRepository channelRepository;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final ClientMessageSender clientMessageSender;

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNEL_MESSAGE_EDIT;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        UUID userId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);

        MessageEditPayload edit = gson.fromJson(payload, MessageEditPayload.class);

        if (edit.getMessageId() == null || edit.getContent() == null) {
            log.warn("CHANNEL_MESSAGE_EDIT denied: missing messageId or content (userId={})", userId);
            return;
        }

        Message message = messageService.findById(edit.getMessageId()).orElse(null);
        if (message == null) {
            log.warn("CHANNEL_MESSAGE_EDIT denied: messageId={} not found (userId={})", edit.getMessageId(), userId);
            return;
        }

        if (!message.getSenderId().equals(userId)) {
            log.warn("CHANNEL_MESSAGE_EDIT denied: userId={} does not own messageId={}", userId, edit.getMessageId());
            return;
        }

        if (edit.getContent().isBlank() && !Boolean.TRUE.equals(message.getHasAttachments())) {
            log.warn("CHANNEL_MESSAGE_EDIT denied: blank content on non-attachment message (userId={})", userId);
            return;
        }

        int maxLength = message.getMessageType() == Message.MessageType.CODE
                ? MAX_CODE_MESSAGE_LENGTH : MAX_MESSAGE_LENGTH;
        if (edit.getContent().length() > maxLength) {
            log.warn("CHANNEL_MESSAGE_EDIT denied: content exceeds {} chars (userId={})", maxLength, userId);
            return;
        }

        UUID channelId = message.getChannelId();
        Channel channel = channelRepository.findById(channelId).orElse(null);
        if (channel == null || !channel.getServerId().equals(serverId)) {
            log.warn("CHANNEL_MESSAGE_EDIT denied: channel mismatch (userId={})", userId);
            return;
        }

        boolean isTextChannel = channel.getChannelType() == Channel.ChannelType.TEXT;
        if (!isTextChannel && !webrtcRoomsManager.isUserInChannel(serverId, channelId, userId)) {
            log.warn("CHANNEL_MESSAGE_EDIT denied: userId={} not connected to voice channelId={}", userId, channelId);
            return;
        }

        Message updated = messageService.editMessage(edit.getMessageId(), edit.getContent().trim(), edit.getCodeLanguage());

        ChannelMessageEditedPayload edited = ChannelMessageEditedPayload.builder()
                .messageId(updated.getMessageId())
                .channelId(updated.getChannelId())
                .serverId(updated.getServerId())
                .content(updated.getContent())
                .codeLanguage(updated.getCodeLanguage())
                .build();

        WsMessage wsMessage = WsMessage.builder()
                .type(WsMessageType.CHANNEL_MESSAGE_EDITED)
                .payload(edited)
                .build();

        if (isTextChannel) {
            clientMessageSender.broadcastToServer(serverId, gson.toJson(wsMessage));
        } else {
            webrtcRoomsManager.broadcastToChannel(serverId, channelId, wsMessage);
        }

        log.info("CHANNEL_MESSAGE_EDIT: userId={} edited messageId={}", userId, updated.getMessageId());
    }
}
