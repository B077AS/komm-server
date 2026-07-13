package com.kommserver.controller;

import com.kommserver.model.db.Channel;
import com.kommserver.model.db.Permission;
import com.kommserver.model.dto.response.AttachmentUploadResponse;
import com.kommserver.model.dto.response.ErrorResponse;
import com.kommserver.repository.ChannelRepository;
import com.kommserver.repository.ServerMemberRepository;
import com.kommserver.security.SecurityUtil;
import com.kommserver.service.MessageService;
import com.kommserver.service.PermissionService;
import com.kommserver.service.ReactionService;
import com.kommserver.websocket.messages.payloads.MessageReceivedPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageService messageService;
    private final ReactionService reactionService;
    private final PermissionService permissionService;
    private final ChannelRepository channelRepository;
    private final ServerMemberRepository serverMemberRepository;
    private final SecurityUtil securityUtil;

    @GetMapping("/{channelId}")
    public ResponseEntity<?> getMessages(
            @PathVariable UUID channelId,
            @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "50") int limit) {
        try {
            UUID userId = securityUtil.getCurrentUserId();
            if (userId == null) {
                return ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Not authenticated");
            }

            Channel channel = channelRepository.findById(channelId).orElse(null);
            if (channel == null) {
                return ErrorResponse.of(HttpStatus.NOT_FOUND, "Channel not found");
            }

            if (!serverMemberRepository.existsByServerIdAndUserId(channel.getServerId(), userId)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "You are not a member of this server");
            }

            limit = Math.min(limit, 100);
            LocalDateTime cursor = before != null
                    ? LocalDateTime.parse(before)
                    : LocalDateTime.now().plusSeconds(1);

            List<MessageReceivedPayload> messages = messageService.getMessagesBefore(channelId, cursor, limit);
            return ResponseEntity.ok(messages);

        } catch (Exception e) {
            log.error("Failed to retrieve messages for channel {}: {}", channelId, e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    @PutMapping("/{messageId}/reactions/{emoji}")
    public ResponseEntity<?> addReaction(
            @PathVariable UUID messageId,
            @PathVariable String emoji) {
        try {
            UUID userId = securityUtil.getCurrentUserId();
            if (userId == null) {
                return ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Not authenticated");
            }

            var message = messageService.findById(messageId).orElse(null);
            if (message == null) return ErrorResponse.of(HttpStatus.NOT_FOUND, "Message not found");

            Channel channel = channelRepository.findById(message.getChannelId()).orElse(null);
            if (channel == null) return ErrorResponse.of(HttpStatus.NOT_FOUND, "Channel not found");

            if (!permissionService.hasInChannel(userId, channel.getServerId(), message.getChannelId(), Permission.ADD_REACTIONS)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: ADD_REACTIONS");
            }

            reactionService.addReaction(messageId, userId, emoji);
            return ResponseEntity.noContent().build();

        } catch (ResponseStatusException e) {
            return ErrorResponse.of((HttpStatus) e.getStatusCode(), e.getReason());
        } catch (Exception e) {
            log.error("Failed to add reaction to message {}: {}", messageId, e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    @DeleteMapping("/{messageId}/reactions/{emoji}")
    public ResponseEntity<?> removeReaction(
            @PathVariable UUID messageId,
            @PathVariable String emoji) {
        try {
            UUID userId = securityUtil.getCurrentUserId();
            if (userId == null) {
                return ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Not authenticated");
            }

            reactionService.removeReaction(messageId, userId, emoji);
            return ResponseEntity.noContent().build();

        } catch (ResponseStatusException e) {
            return ErrorResponse.of((HttpStatus) e.getStatusCode(), e.getReason());
        } catch (Exception e) {
            log.error("Failed to remove reaction to message {}: {}", messageId, e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    @PostMapping("/channels/{channelId}/attachments")
    public ResponseEntity<?> uploadAttachment(
            @PathVariable UUID channelId,
            @RequestParam("file") MultipartFile file) {
        try {
            UUID userId = securityUtil.getCurrentUserId();
            if (userId == null) return ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Not authenticated");

            Channel channel = channelRepository.findById(channelId).orElse(null);
            if (channel == null) return ErrorResponse.of(HttpStatus.NOT_FOUND, "Channel not found");

            if (!serverMemberRepository.existsByServerIdAndUserId(channel.getServerId(), userId))
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Not a member of this server");

            if (!permissionService.hasInChannel(userId, channel.getServerId(), channelId, Permission.SEND_ATTACHMENTS))
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: SEND_ATTACHMENTS");

            if (file.isEmpty()) return ErrorResponse.of(HttpStatus.BAD_REQUEST, "File is empty");

            String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
            AttachmentUploadResponse response = messageService.uploadChannelAttachment(
                    channelId, userId, file.getBytes(),
                    file.getOriginalFilename() != null ? file.getOriginalFilename() : "attachment",
                    contentType);

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (Exception e) {
            log.error("Failed to upload attachment to channel {}: {}", channelId, e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    @GetMapping("/{messageId}/attachment")
    public ResponseEntity<?> downloadAttachment(@PathVariable UUID messageId) {
        try {
            UUID userId = securityUtil.getCurrentUserId();
            if (userId == null) {
                return ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Not authenticated");
            }

            return messageService.getAttachmentResource(messageId, userId);

        } catch (Exception e) {
            log.error("Failed to download attachment for message {}: {}", messageId, e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }
}