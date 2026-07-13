package com.kommserver.controller;

import com.google.gson.Gson;
import com.kommserver.model.db.Permission;
import com.kommserver.model.dto.request.SoundboardEditRequest;
import com.kommserver.model.dto.request.SoundboardUploadRequest;
import com.kommserver.model.dto.response.ErrorResponse;
import com.kommserver.model.dto.summary.SoundboardSummary;
import com.kommserver.repository.ServerMemberRepository;
import com.kommserver.security.SecurityUtil;
import com.kommserver.service.PermissionService;
import com.kommserver.service.SoundboardService;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.SoundboardUpdatedPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/soundboards")
public class SoundboardController {

    private static final int MAX_SOUNDBOARD_NAME_LENGTH = 50;

    private final SecurityUtil securityUtil;
    private final PermissionService permissionService;
    private final SoundboardService soundboardService;
    private final ServerMemberRepository serverMemberRepository;
    private final ClientMessageSender clientMessageSender;
    private final Gson gson;

    @GetMapping
    public ResponseEntity<?> list() {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();
            if (!serverMemberRepository.existsByServerIdAndUserId(serverId, userId)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Not a member of this server");
            }
            return ResponseEntity.ok(soundboardService.listServer(serverId));
        } catch (Exception e) {
            log.error("Failed to list soundboards: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PostMapping
    public ResponseEntity<?> upload(@RequestBody SoundboardUploadRequest req) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();
            if (!permissionService.has(userId, serverId, Permission.MANAGE_SERVER_SOUNDBOARD)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: MANAGE_SERVER_SOUNDBOARD");
            }
            if (req.getContentBase64() == null || req.getContentBase64().isBlank()) {
                return ErrorResponse.of(HttpStatus.BAD_REQUEST, "No file content");
            }
            if (req.getName() != null && req.getName().trim().length() > MAX_SOUNDBOARD_NAME_LENGTH) {
                return ErrorResponse.of(HttpStatus.BAD_REQUEST,
                        "Soundboard name exceeds " + MAX_SOUNDBOARD_NAME_LENGTH + " characters");
            }
            SoundboardSummary summary = soundboardService.upload(serverId, userId,
                    req.getSlotIndex(), req.getName(), req.getEmoji(), req.getFileName(), req.getFileType(), req.getContentBase64());
            broadcastServerUpdated(serverId);
            return ResponseEntity.ok(summary);
        } catch (IllegalArgumentException e) {
            return ErrorResponse.of(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to upload soundboard: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PatchMapping("/{soundboardId}")
    public ResponseEntity<?> edit(@PathVariable UUID soundboardId, @RequestBody SoundboardEditRequest req) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();
            SoundboardService.Details sb = soundboardService.find(serverId, soundboardId).orElse(null);
            if (sb == null) return ErrorResponse.of(HttpStatus.NOT_FOUND, "Soundboard not found");
            if (!permissionService.has(userId, serverId, Permission.MANAGE_SERVER_SOUNDBOARD)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: MANAGE_SERVER_SOUNDBOARD");
            }
            if (req.getName() != null && req.getName().trim().length() > MAX_SOUNDBOARD_NAME_LENGTH) {
                return ErrorResponse.of(HttpStatus.BAD_REQUEST,
                        "Soundboard name exceeds " + MAX_SOUNDBOARD_NAME_LENGTH + " characters");
            }
            SoundboardSummary summary = soundboardService.edit(serverId, soundboardId,
                    req.getName(), req.getEmoji(), req.getFileName(), req.getFileType(), req.getContentBase64());
            broadcastServerUpdated(serverId);
            return ResponseEntity.ok(summary);
        } catch (IllegalArgumentException e) {
            return ErrorResponse.of(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to edit soundboard {}: {}", soundboardId, e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @DeleteMapping("/{soundboardId}")
    public ResponseEntity<?> delete(@PathVariable UUID soundboardId) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();
            SoundboardService.Details sb = soundboardService.find(serverId, soundboardId).orElse(null);
            if (sb == null) return ErrorResponse.of(HttpStatus.NOT_FOUND, "Soundboard not found");
            if (!permissionService.has(userId, serverId, Permission.MANAGE_SERVER_SOUNDBOARD)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: MANAGE_SERVER_SOUNDBOARD");
            }
            soundboardService.delete(serverId, soundboardId);
            broadcastServerUpdated(serverId);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Failed to delete soundboard {}: {}", soundboardId, e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @GetMapping("/{soundboardId}/file")
    public ResponseEntity<?> download(@PathVariable UUID soundboardId) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();
            if (!permissionService.has(userId, serverId, Permission.USE_SOUNDBOARD)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: USE_SOUNDBOARD");
            }
            return soundboardService.getFileResource(serverId, soundboardId);
        } catch (Exception e) {
            log.error("Failed to download soundboard {}: {}", soundboardId, e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private void broadcastServerUpdated(UUID serverId) {
        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.SOUNDBOARD_UPDATED)
                .payload(SoundboardUpdatedPayload.builder()
                        .serverId(serverId)
                        .soundboards(soundboardService.listServer(serverId))
                        .build())
                .build();
        clientMessageSender.broadcastToServer(serverId, gson.toJson(msg));
    }
}
