package com.kommserver.controller;

import com.google.gson.Gson;
import com.kommserver.model.db.Permission;
import com.kommserver.model.dto.request.BotCreateRequest;
import com.kommserver.model.dto.request.BotUpdateRequest;
import com.kommserver.model.dto.response.ErrorResponse;
import com.kommserver.model.dto.summary.BotSummary;
import com.kommserver.security.SecurityUtil;
import com.kommserver.service.BotService;
import com.kommserver.service.PermissionService;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.BotsUpdatedPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/bots")
public class BotController {

    private final SecurityUtil securityUtil;
    private final BotService botService;
    private final PermissionService permissionService;
    private final ClientMessageSender clientMessageSender;
    private final Gson gson;

    @GetMapping
    public ResponseEntity<?> listBots() {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            return ResponseEntity.ok(botService.getServerBots(serverId));
        } catch (Exception e) {
            log.error("Failed to list bots: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    @PostMapping
    public ResponseEntity<?> createBot(@RequestBody BotCreateRequest request) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();

            if (!permissionService.has(userId, serverId, Permission.MANAGE_BOTS)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: MANAGE_BOTS");
            }
            if (request.getName() == null || request.getName().isBlank()) {
                return ErrorResponse.of(HttpStatus.BAD_REQUEST, "Bot name is required");
            }
            if (request.getBotType() == null) {
                return ErrorResponse.of(HttpStatus.BAD_REQUEST, "Bot type is required");
            }

            BotSummary created = botService.createBot(serverId, request);
            broadcastRoster(serverId);
            return ResponseEntity.ok(created);
        } catch (Exception e) {
            log.error("Failed to create bot: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    @PutMapping("/{botId}")
    public ResponseEntity<?> updateBot(@PathVariable UUID botId, @RequestBody BotUpdateRequest request) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();

            if (!permissionService.has(userId, serverId, Permission.MANAGE_BOTS)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: MANAGE_BOTS");
            }

            Optional<BotSummary> updated = botService.updateBot(serverId, botId, request);
            if (updated.isEmpty()) {
                return ErrorResponse.of(HttpStatus.NOT_FOUND, "Bot not found");
            }
            broadcastRoster(serverId);
            return ResponseEntity.ok(updated.get());
        } catch (Exception e) {
            log.error("Failed to update bot {}: {}", botId, e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    @PutMapping("/{botId}/channels")
    public ResponseEntity<?> assignChannels(@PathVariable UUID botId, @RequestBody List<UUID> channelIds) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();

            if (!permissionService.has(userId, serverId, Permission.MANAGE_BOTS)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: MANAGE_BOTS");
            }

            Optional<BotSummary> updated = botService.assignChannels(serverId, botId, channelIds);
            if (updated.isEmpty()) {
                return ErrorResponse.of(HttpStatus.NOT_FOUND, "Bot not found");
            }
            broadcastRoster(serverId);
            return ResponseEntity.ok(updated.get());
        } catch (Exception e) {
            log.error("Failed to reassign channels for bot {}: {}", botId, e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    @DeleteMapping("/{botId}")
    public ResponseEntity<?> deleteBot(@PathVariable UUID botId) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();

            if (!permissionService.has(userId, serverId, Permission.MANAGE_BOTS)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: MANAGE_BOTS");
            }

            boolean deleted = botService.deleteBot(serverId, botId);
            if (!deleted) {
                return ErrorResponse.of(HttpStatus.NOT_FOUND, "Bot not found");
            }
            broadcastRoster(serverId);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Failed to delete bot {}: {}", botId, e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    private void broadcastRoster(UUID serverId) {
        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.BOTS_UPDATED)
                .payload(BotsUpdatedPayload.builder()
                        .serverId(serverId)
                        .bots(botService.getServerBots(serverId))
                        .build())
                .build();
        clientMessageSender.broadcastToServer(serverId, gson.toJson(msg));
    }
}
