package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.Channel;
import com.kommserver.model.db.Message;
import com.kommserver.model.db.MessageAttachment;
import com.kommserver.model.db.Permission;
import com.kommserver.model.db.ServerMember;
import com.kommserver.repository.ChannelRepository;
import com.kommserver.repository.MessageAttachmentRepository;
import com.kommserver.repository.MessageRepository;
import com.kommserver.repository.ServerMemberRepository;
import com.kommserver.service.PermissionService;
import com.kommserver.websocket.senders.ClientMessageSender;
import com.kommserver.websocket.managers.ClientSessionManager;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.ChannelMessageDeletedPayload;
import com.kommserver.websocket.messages.payloads.MessageDeletePayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelMessageDeleteHandler implements ClientInboundMessageHandler {

    private final Gson gson;
    private final MessageRepository messageRepository;
    private final ChannelRepository channelRepository;
    private final ServerMemberRepository serverMemberRepository;
    private final MessageAttachmentRepository messageAttachmentRepository;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final ClientMessageSender clientMessageSender;
    private final PermissionService permissionService;

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNEL_MESSAGE_DELETE;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        UUID userId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);

        MessageDeletePayload delete = gson.fromJson(payload, MessageDeletePayload.class);

        // Fetch the message — needed for ownership, channel, and type checks
        Message message = messageRepository.findById(delete.getMessageId()).orElse(null);
        if (message == null) {
            log.warn("CHANNEL_MESSAGE_DELETE denied: messageId={} not found (userId={})", delete.getMessageId(), userId);
            return;
        }

        UUID channelId = message.getChannelId();

        // Verify the channel exists and belongs to the server from the session
        Channel channel = channelRepository.findById(channelId).orElse(null);
        if (channel == null || !channel.getServerId().equals(serverId)) {
            log.warn("CHANNEL_MESSAGE_DELETE denied: channelId={} does not exist or does not belong to serverId={} (userId={})",
                    channelId, serverId, userId);
            return;
        }

        // Verify the user is a member of that server
        ServerMember member = serverMemberRepository.findById(
                new ServerMember.ServerMemberId(userId, serverId)).orElse(null);
        if (member == null) {
            log.warn("CHANNEL_MESSAGE_DELETE denied: userId={} is not a member of serverId={}", userId, serverId);
            return;
        }

        boolean canDeleteOthers = permissionService.hasInChannel(userId, serverId, channelId, Permission.DELETE_OTHERS_MSGS);

        if (!canDeleteOthers && !message.getSenderId().equals(userId)) {
            log.warn("CHANNEL_MESSAGE_DELETE denied: userId={} does not own messageId={} and is not privileged",
                    userId, delete.getMessageId());
            return;
        }

        boolean isTextChannel = channel.getChannelType() == Channel.ChannelType.TEXT;

        if (!isTextChannel && !webrtcRoomsManager.isUserInChannel(serverId, channelId, userId)) {
            log.warn("CHANNEL_MESSAGE_DELETE denied: userId={} is not connected to voice channelId={}", userId, channelId);
            return;
        }

        // Delete attachments from disk and DB before deleting the message
        deleteAttachments(delete.getMessageId());

        messageRepository.deleteById(delete.getMessageId());

        ChannelMessageDeletedPayload deleted = ChannelMessageDeletedPayload.builder()
                .messageId(delete.getMessageId())
                .channelId(channelId)
                .serverId(serverId)
                .build();

        WsMessage wsMessage = WsMessage.builder()
                .type(WsMessageType.CHANNEL_MESSAGE_DELETED)
                .payload(deleted)
                .build();

        if (isTextChannel) {
            clientMessageSender.broadcastToServer(serverId, gson.toJson(wsMessage));
        } else {
            webrtcRoomsManager.broadcastToChannel(serverId, channelId, wsMessage);
        }

        log.info("CHANNEL_MESSAGE_DELETE: userId={} deleted messageId={} in channelId={}", userId, delete.getMessageId(), channelId);
    }

    /**
     * Deletes all attachments tied to the given message — from disk first, then from the DB.
     * In practice there is at most one attachment per message, but the method handles all rows
     * returned by the repository defensively.
     */
    private void deleteAttachments(UUID messageId) {
        List<MessageAttachment> attachments = messageAttachmentRepository.findByMessageIdIn(List.of(messageId));
        if (attachments.isEmpty()) return;

        for (MessageAttachment attachment : attachments) {
            if (attachment.getFilePath() != null) {
                try {
                    Path path = Paths.get(attachment.getFilePath());
                    boolean deleted = Files.deleteIfExists(path);
                    if (deleted) {
                        log.info("Deleted attachment file: {}", path);
                    } else {
                        log.warn("Attachment file not found on disk (already gone?): {}", path);
                    }
                } catch (IOException e) {
                    // Log and continue — we still want the DB row removed
                    log.error("Failed to delete attachment file for attachmentId={} path={}",
                            attachment.getAttachmentId(), attachment.getFilePath(), e);
                }
            }
        }

        messageAttachmentRepository.deleteAll(attachments);
        log.info("Deleted {} attachment record(s) for messageId={}", attachments.size(), messageId);
    }
}