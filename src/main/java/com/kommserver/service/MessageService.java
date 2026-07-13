package com.kommserver.service;

import com.kommserver.model.db.ChannelRead;
import com.kommserver.model.db.Message;
import com.kommserver.model.db.MessageAttachment;
import com.kommserver.model.db.PendingChannelAttachment;
import com.kommserver.model.dto.response.AttachmentUploadResponse;
import com.kommserver.model.dto.response.ErrorResponse;
import com.kommserver.repository.ChannelReadRepository;
import com.kommserver.repository.MessageAttachmentRepository;
import com.kommserver.repository.MessageReactionRepository;
import com.kommserver.repository.MessageRepository;
import com.kommserver.repository.PendingChannelAttachmentRepository;
import com.kommserver.websocket.messages.payloads.ChannelMessageReactionAdd;
import com.kommserver.websocket.messages.payloads.MessageReceivedPayload;
import com.kommserver.websocket.messages.payloads.MessageSentPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.apache.commons.io.FilenameUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final MessageAttachmentRepository messageAttachmentRepository;
    private final MessageReactionRepository messageReactionRepository;
    private final PendingChannelAttachmentRepository pendingAttachmentRepository;
    private final ChannelReadRepository channelReadRepository;

    @Value("${komm.attachments.base-path}")
    private String attachmentsBasePath;

    public AttachmentUploadResponse uploadChannelAttachment(UUID channelId, UUID uploaderId,
                                                            byte[] fileBytes, String originalFileName,
                                                            String contentType) throws IOException {
        String safeFileName = sanitizeFileName(originalFileName);
        String extension = FilenameUtils.getExtension(originalFileName);
        String diskFileName = UUID.randomUUID() + (extension.isBlank() ? "" : "." + extension);

        Path dir = Paths.get(attachmentsBasePath, channelId.toString());
        Files.createDirectories(dir);
        Path filePath = dir.resolve(diskFileName);
        Files.write(filePath, fileBytes);

        PendingChannelAttachment saved = pendingAttachmentRepository.save(PendingChannelAttachment.builder()
                .channelId(channelId)
                .uploaderId(uploaderId)
                .filePath(filePath.toString())
                .fileName(safeFileName)
                .fileSize((long) fileBytes.length)
                .fileType(contentType != null ? contentType : "application/octet-stream")
                .uploadedAt(LocalDateTime.now())
                .build());

        return AttachmentUploadResponse.builder().attachmentId(saved.getAttachmentId()).build();
    }

    public Optional<Message> findById(UUID messageId) {
        return messageRepository.findById(messageId);
    }

    public List<MessageReceivedPayload> getMessagesBefore(UUID channelId, LocalDateTime cursor, int limit) {
        List<Message> messages = messageRepository.findByChannelIdBeforeCursor(
                channelId, cursor, PageRequest.of(0, limit));

        if (messages.isEmpty()) return List.of();

        List<UUID> ids = messages.stream()
                .map(Message::getMessageId)
                .toList();

        // Load messages with their reactions
        Map<UUID, Message> withReactions = messageRepository.findByIdsWithReactions(ids)
                .stream()
                .collect(Collectors.toMap(Message::getMessageId, m -> m));

        // Load attachments for all messages in batch
        Map<UUID, List<MessageAttachment>> attachmentsByMessage = messageAttachmentRepository
                .findByMessageIdIn(ids)
                .stream()
                .collect(Collectors.groupingBy(MessageAttachment::getMessageId));

        return messages.stream()
                .map(m -> toPayload(
                        withReactions.getOrDefault(m.getMessageId(), m),
                        attachmentsByMessage.getOrDefault(m.getMessageId(), Collections.emptyList())
                ))
                .toList();
    }

    public Message editMessage(UUID messageId, String newContent, String codeLanguage) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Message not found: " + messageId));
        message.setContent(newContent);
        message.setIsEdited(true);
        if (codeLanguage != null) {
            message.setCodeLanguage(codeLanguage);
        }
        return messageRepository.save(message);
    }

    public Message save(UUID userId, UUID serverId, MessageSentPayload sent) {
        return messageRepository.save(Message.builder()
                .senderId(userId)
                .serverId(serverId)
                .channelId(sent.getChannelId())
                .content(sent.getContent())
                .sentAt(LocalDateTime.now())
                .isEdited(false)
                .hasAttachments(sent.isHasAttachments())
                .repliedToId(sent.getRepliedToId())
                .messageType(sent.getMessageType())
                .codeLanguage(sent.getCodeLanguage())
                .build());
    }

    // ── Read state ────────────────────────────────────────────────────────────

    public Set<UUID> getUnreadChannelIds(UUID userId, Collection<UUID> channelIds) {
        if (channelIds.isEmpty()) return Collections.emptySet();

        List<Message> latestPerChannel = messageRepository.findLatestMessagePerChannel(List.copyOf(channelIds));

        Map<UUID, ChannelRead> readMap = channelReadRepository.findAllByUserId(userId)
                .stream().collect(Collectors.toMap(ChannelRead::getChannelId, r -> r));

        Set<UUID> unread = new HashSet<>();
        for (Message m : latestPerChannel) {
            if (m.getSenderId().equals(userId)) continue;
            ChannelRead read = readMap.get(m.getChannelId());
            if (read == null || m.getSentAt().isAfter(read.getLastReadAt())) {
                unread.add(m.getChannelId());
            }
        }
        return unread;
    }

    @Transactional
    public void markChannelRead(UUID userId, UUID channelId) {
        ChannelRead record = channelReadRepository.findByUserIdAndChannelId(userId, channelId)
                .orElse(ChannelRead.builder().userId(userId).channelId(channelId).build());
        record.setLastReadAt(LocalDateTime.now());
        channelReadRepository.save(record);
    }

    private MessageReceivedPayload toPayload(Message message, List<MessageAttachment> attachments) {
        MessageReceivedPayload.MessageReceivedPayloadBuilder builder = MessageReceivedPayload.builder()
                .messageId(message.getMessageId())
                .senderId(message.getSenderId())
                .channelId(message.getChannelId())
                .serverId(message.getServerId())
                .content(message.getContent())
                .sentAt(message.getSentAt())
                .edited(message.getIsEdited())
                .hasAttachments(message.getHasAttachments())
                .repliedToId(message.getRepliedToId())
                .messageType(message.getMessageType())
                .codeLanguage(message.getCodeLanguage());

        // Add reply info if present
        if (message.getRepliedToId() != null) {
            messageRepository.findById(message.getRepliedToId()).ifPresent(replied -> {
                builder.replyToSenderId(replied.getSenderId());
                builder.replyToContent(replied.getContent());
                builder.replyToMessageType(replied.getMessageType());
                boolean replyHasAtts = Boolean.TRUE.equals(replied.getHasAttachments());
                builder.replyToHasAttachments(replyHasAtts);
                if (replyHasAtts) {
                    List<MessageAttachment> replyAtts = messageAttachmentRepository
                            .findByMessageIdIn(List.of(replied.getMessageId()));
                    if (!replyAtts.isEmpty()) {
                        builder.replyToFileName(replyAtts.get(0).getFileName());
                        builder.replyToFileType(replyAtts.get(0).getFileType());
                    }
                }
            });
        }

        // Add attachment info with base64 content ONLY for images
        if (message.getHasAttachments() && !attachments.isEmpty()) {
            MessageAttachment attachment = attachments.get(0); // Take first attachment

            builder.fileName(attachment.getFileName())
                    .fileType(attachment.getFileType())
                    .fileSize(attachment.getFileSize());

            // Only include base64 content for images
            if (isImageType(attachment.getFileType())) {
                String base64Content = readFileAsBase64(attachment.getFilePath());
                builder.file64(base64Content);

                if (base64Content == null) {
                    log.warn("Failed to load image attachment for message {}", message.getMessageId());
                }
            } else {
                // For non-images, don't include base64 (just metadata)
                log.debug("Skipping base64 for non-image attachment: {} (type: {})",
                        attachment.getFileName(), attachment.getFileType());
                builder.file64(null);
            }
        }

        // Add reactions
        List<ChannelMessageReactionAdd> reactions = message.getReactions() == null || message.getReactions().isEmpty()
                ? Collections.emptyList()
                : message.getReactions().stream()
                  .map(r -> ChannelMessageReactionAdd.builder()
                            .messageId(message.getMessageId())
                            .serverId(message.getServerId())
                            .channelId(message.getChannelId())
                            .userId(r.getId().getUserId())
                            .emoji(r.getId().getEmoji())
                            .build())
                  .collect(Collectors.toList());

        builder.reactions(reactions);

        return builder.build();
    }

    public ResponseEntity<?> getAttachmentResource(UUID messageId, UUID userId) {
        Message message = messageRepository.findById(messageId).orElse(null);
        if (message == null) {
            return ErrorResponse.of(HttpStatus.NOT_FOUND, "Message not found");
        }

        if (!message.getHasAttachments()) {
            return ErrorResponse.of(HttpStatus.NOT_FOUND, "Message has no attachment");
        }

        List<MessageAttachment> attachments = messageAttachmentRepository.findByMessageIdIn(List.of(messageId));
        if (attachments.isEmpty()) {
            return ErrorResponse.of(HttpStatus.NOT_FOUND, "Attachment not found");
        }

        MessageAttachment attachment = attachments.get(0);
        Path filePath = Paths.get(attachment.getFilePath());

        if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
            log.error("Attachment file missing or unreadable: {}", filePath);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Attachment file unavailable");
        }

        try {
            byte[] bytes = Files.readAllBytes(filePath);
            String mime = attachment.getFileType() != null ? attachment.getFileType() : "application/octet-stream";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + attachment.getFileName() + "\"")
                    .contentType(MediaType.parseMediaType(mime))
                    .contentLength(bytes.length)
                    .body(bytes);

        } catch (IOException e) {
            log.error("Failed to read attachment file for message {}", messageId, e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read attachment");
        }
    }

    /**
     * Checks if the file type is an image.
     *
     * @param fileType MIME type of the file
     * @return true if it's an image type
     */
    private boolean isImageType(String fileType) {
        if (fileType == null) return false;
        return fileType.startsWith("image/");
    }

    @Transactional
    public void deleteAllInChannel(UUID channelId) {
        List<UUID> messageIds = messageRepository.findMessageIdsByChannelId(channelId);
        if (messageIds.isEmpty()) return;

        List<MessageAttachment> attachments = messageAttachmentRepository.findByMessageIdIn(messageIds);
        for (MessageAttachment att : attachments) {
            if (att.getFilePath() != null) {
                try {
                    Files.deleteIfExists(Paths.get(att.getFilePath()));
                } catch (IOException e) {
                    log.warn("Could not delete attachment file {}: {}", att.getFilePath(), e.getMessage());
                }
            }
        }

        messageReactionRepository.deleteByMessageIdIn(messageIds);
        messageAttachmentRepository.deleteByMessageIdIn(messageIds);
        messageRepository.deleteByChannelId(channelId);
        log.info("Deleted {} messages ({} attachments) for channelId={}", messageIds.size(), attachments.size(), channelId);
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    private String readFileAsBase64(String storedFilePath) {
        try {
            Path filePath = Paths.get(storedFilePath);
            if (Files.exists(filePath) && Files.isReadable(filePath)) {
                byte[] fileBytes = Files.readAllBytes(filePath);
                return Base64.getEncoder().encodeToString(fileBytes);
            } else {
                log.error("File not found or not readable: {}", filePath);
                return null;
            }
        } catch (IOException e) {
            log.error("Failed to read attachment file at path {}", storedFilePath, e);
            return null;
        }
    }
}