package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.Channel;
import com.kommserver.model.db.Message;
import com.kommserver.model.db.MessageAttachment;
import com.kommserver.model.db.PendingChannelAttachment;
import com.kommserver.model.db.Permission;
import com.kommserver.repository.ChannelRepository;
import com.kommserver.repository.MessageAttachmentRepository;
import com.kommserver.repository.PendingChannelAttachmentRepository;
import com.kommserver.repository.ServerMemberRepository;
import com.kommserver.service.MessageService;
import com.kommserver.service.PermissionService;
import com.kommserver.websocket.senders.ClientMessageSender;
import com.kommserver.websocket.managers.ClientSessionManager;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.MessageReceivedPayload;
import com.kommserver.websocket.messages.payloads.MessageSentPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelMessageSentHandler implements ClientInboundMessageHandler {

    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_CODE_MESSAGE_LENGTH = 50000;

    private final Gson gson;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final MessageService messageService;
    private final ChannelRepository channelRepository;
    private final ServerMemberRepository serverMemberRepository;
    private final MessageAttachmentRepository messageAttachmentRepository;
    private final PendingChannelAttachmentRepository pendingAttachmentRepository;
    private final ClientMessageSender clientMessageSender;
    private final PermissionService permissionService;

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNEL_MESSAGE_SENT;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        UUID userId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);

        MessageSentPayload sent = gson.fromJson(payload, MessageSentPayload.class);

        int maxLength = sent.getMessageType() == Message.MessageType.CODE
                ? MAX_CODE_MESSAGE_LENGTH : MAX_MESSAGE_LENGTH;
        if (sent.getContent() != null && sent.getContent().length() > maxLength) {
            log.warn("CHANNEL_MESSAGE_SENT denied: content exceeds {} chars (userId={})", maxLength, userId);
            return;
        }

        Channel channel = channelRepository.findById(sent.getChannelId()).orElse(null);
        if (channel == null || !channel.getServerId().equals(serverId)) {
            log.warn("CHANNEL_MESSAGE_SENT denied: channelId={} does not belong to serverId={} (userId={})",
                    sent.getChannelId(), serverId, userId);
            return;
        }

        if (!serverMemberRepository.existsByServerIdAndUserId(serverId, userId)) {
            log.warn("CHANNEL_MESSAGE_SENT denied: userId={} is not a member of serverId={}", userId, serverId);
            return;
        }

        if (!permissionService.hasInChannel(userId, serverId, sent.getChannelId(), Permission.SEND_MESSAGES)) {
            log.warn("CHANNEL_MESSAGE_SENT denied: userId={} missing SEND_MESSAGES in channelId={}", userId, sent.getChannelId());
            return;
        }

        if (sent.isHasAttachments()
                && !permissionService.hasInChannel(userId, serverId, sent.getChannelId(), Permission.SEND_ATTACHMENTS)) {
            log.warn("CHANNEL_MESSAGE_SENT denied: userId={} missing SEND_ATTACHMENTS in channelId={}", userId, sent.getChannelId());
            return;
        }

        if (sent.getMessageType() == Message.MessageType.GIF
                && !permissionService.hasInChannel(userId, serverId, sent.getChannelId(), Permission.SEND_GIFS)) {
            log.warn("CHANNEL_MESSAGE_SENT denied: userId={} missing SEND_GIFS in channelId={}", userId, sent.getChannelId());
            return;
        }

        boolean isTextChannel = channel.getChannelType() == Channel.ChannelType.TEXT;
        if (!isTextChannel && !webrtcRoomsManager.isUserInChannel(serverId, sent.getChannelId(), userId)) {
            log.warn("CHANNEL_MESSAGE_SENT denied: userId={} is not connected to voice channelId={}", userId, sent.getChannelId());
            return;
        }

        Message saved = messageService.save(userId, serverId, sent);
        log.info("Saved message ID: {}", saved.getMessageId());

        // ── Resolve pending attachment ─────────────────────────────────────────
        String persistedFile64 = null;
        String persistedFileName = null;
        String persistedFileType = null;
        long persistedFileSize = 0;

        if (sent.isHasAttachments() && sent.getAttachmentId() != null) {
            PendingChannelAttachment pending = pendingAttachmentRepository.findById(sent.getAttachmentId()).orElse(null);
            if (pending == null || !pending.getChannelId().equals(sent.getChannelId())
                    || !pending.getUploaderId().equals(userId)) {
                log.warn("CHANNEL_MESSAGE_SENT: invalid or missing pending attachment {} for userId={}", sent.getAttachmentId(), userId);
            } else {
                MessageAttachment attachment = MessageAttachment.builder()
                        .messageId(saved.getMessageId())
                        .filePath(pending.getFilePath())
                        .fileName(pending.getFileName())
                        .fileSize(pending.getFileSize())
                        .fileType(pending.getFileType())
                        .build();
                messageAttachmentRepository.save(attachment);
                pendingAttachmentRepository.delete(pending);

                persistedFileName = pending.getFileName();
                persistedFileType = pending.getFileType();
                persistedFileSize = pending.getFileSize();

                if (isImageType(persistedFileType)) {
                    persistedFile64 = readFileAsBase64(pending.getFilePath());
                }

                log.info("Attachment linked: path={} size={} type={}", pending.getFilePath(), pending.getFileSize(), pending.getFileType());
            }
        }

        // ── Build reply context ───────────────────────────────────────────────
        UUID replyToSenderId = null;
        String replyToContent = null;
        Message.MessageType replyToMessageType = null;
        boolean replyToHasAttachments = false;
        String replyToFileName = null;
        String replyToFileType = null;
        if (saved.getRepliedToId() != null) {
            Message repliedTo = messageService.findById(saved.getRepliedToId()).orElse(null);
            if (repliedTo != null) {
                replyToSenderId = repliedTo.getSenderId();
                replyToContent = repliedTo.getContent();
                replyToMessageType = repliedTo.getMessageType();
                replyToHasAttachments = Boolean.TRUE.equals(repliedTo.getHasAttachments());
                if (replyToHasAttachments) {
                    List<MessageAttachment> replyAtts = messageAttachmentRepository
                            .findByMessageIdIn(List.of(saved.getRepliedToId()));
                    if (!replyAtts.isEmpty()) {
                        replyToFileName = replyAtts.get(0).getFileName();
                        replyToFileType = replyAtts.get(0).getFileType();
                    }
                }
            }
        }

        // ── Broadcast ─────────────────────────────────────────────────────────
        MessageReceivedPayload received = MessageReceivedPayload.builder()
                .messageId(saved.getMessageId())
                .senderId(saved.getSenderId())
                .channelId(saved.getChannelId())
                .serverId(saved.getServerId())
                .content(saved.getContent())
                .sentAt(saved.getSentAt())
                .edited(saved.getIsEdited())
                .repliedToId(saved.getRepliedToId())
                .hasAttachments(saved.getHasAttachments())
                .replyToSenderId(replyToSenderId)
                .replyToContent(replyToContent)
                .replyToMessageType(replyToMessageType)
                .replyToHasAttachments(replyToHasAttachments)
                .replyToFileName(replyToFileName)
                .replyToFileType(replyToFileType)
                .messageType(saved.getMessageType())
                .codeLanguage(saved.getCodeLanguage())
                .fileName(persistedFileName)
                .fileType(persistedFileType)
                .fileSize(persistedFileSize)
                .file64(persistedFile64)
                .build();

        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.CHANNEL_MESSAGE_RECEIVED)
                .payload(received)
                .build();

        if (isTextChannel) {
            clientMessageSender.broadcastToServer(serverId, gson.toJson(msg));
        } else {
            webrtcRoomsManager.broadcastToChannel(serverId, sent.getChannelId(), msg);
        }
    }

    private boolean isImageType(String fileType) {
        return fileType != null && fileType.startsWith("image/");
    }

    private String readFileAsBase64(String filePath) {
        try {
            var path = Paths.get(filePath);
            if (Files.exists(path) && Files.isReadable(path)) {
                return Base64.getEncoder().encodeToString(Files.readAllBytes(path));
            }
        } catch (Exception e) {
            log.error("Failed to read attachment file at {}", filePath, e);
        }
        return null;
    }
}
