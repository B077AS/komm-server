package com.kommserver.controller;

import com.google.gson.Gson;
import com.kommserver.model.db.Permission;
import com.kommserver.model.db.Channel.ChannelType;
import com.kommserver.model.dto.request.ChannelCreateRequest;
import com.kommserver.model.dto.request.ChannelUpdateRequest;
import com.kommserver.model.dto.response.ErrorResponse;
import com.kommserver.model.dto.summary.ChannelSummary;
import com.kommserver.security.SecurityUtil;
import com.kommserver.service.ChannelService;
import com.kommserver.service.MessageService;
import com.kommserver.service.PermissionService;
import com.kommserver.sfu.LiveKitTokenService;
import com.kommserver.websocket.senders.ClientMessageSender;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.messages.UserSessionEntry;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.ChannelCreatedPayload;
import com.kommserver.websocket.messages.payloads.ChannelUpdatedPayload;
import com.kommserver.websocket.messages.payloads.ChannelDeletedPayload;
import com.kommserver.websocket.messages.payloads.ChannelsReorderedPayload;
import com.kommserver.websocket.messages.payloads.StreamEndedPayload;
import com.kommserver.websocket.messages.payloads.StreamViewerCountPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/channels")
public class ChannelController {

    private final SecurityUtil securityUtil;
    private final ChannelService channelService;
    private final MessageService messageService;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final LiveKitTokenService liveKitTokenService;
    private final ClientMessageSender clientMessageSender;
    private final PermissionService permissionService;
    private final Gson gson;

    private static boolean containsEmoji(String text) {
        return text.codePoints().anyMatch(cp ->
                cp > 0xFFFF
                || Character.getType(cp) == Character.OTHER_SYMBOL
                || (cp >= 0x2300 && cp <= 0x23FF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0x1F000 && cp <= 0x1FFFF));
    }

    @GetMapping("/list")
    public ResponseEntity<?> getServerChannels() {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();
            Map<UUID, ChannelSummary> all = channelService.getServerChannels(serverId);
            Map<UUID, ChannelSummary> visible = all.entrySet().stream()
                    .filter(e -> {
                        ChannelSummary.ChannelType t = e.getValue().getChannelType();
                        boolean isDecoration = t == ChannelSummary.ChannelType.SPACER
                                || t == ChannelSummary.ChannelType.DIVIDER
                                || t == ChannelSummary.ChannelType.TITLE;
                        return isDecoration || permissionService.hasInChannel(
                                userId, serverId, e.getKey(), Permission.VIEW_CHANNEL);
                    })
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            Set<UUID> unreadChannelIds = messageService.getUnreadChannelIds(userId, visible.keySet());
            unreadChannelIds.forEach(id -> {
                ChannelSummary c = visible.get(id);
                if (c != null) c.setHasUnread(true);
            });

            return ResponseEntity.ok(visible);
        } catch (Exception e) {
            log.error("Failed to retrieve channels: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    @PutMapping("/{channelId}/read")
    public ResponseEntity<?> markChannelRead(@PathVariable UUID channelId) {
        try {
            UUID userId = securityUtil.getCurrentUserId();
            messageService.markChannelRead(userId, channelId);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Failed to mark channel {} as read: {}", channelId, e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    @GetMapping("/{channelId}/users")
    public ResponseEntity<?> getConnectedUsers(@PathVariable UUID channelId) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            Collection<UserSessionEntry> userIds = webrtcRoomsManager.getEntriesInChannel(serverId, channelId);
            return ResponseEntity.ok(userIds);
        } catch (Exception e) {
            log.error("Failed to retrieve connected users: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    @PutMapping("/reorder")
    public ResponseEntity<?> reorderChannels(@RequestBody List<UUID> channelIds) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();

            if (!permissionService.has(userId, serverId, Permission.EDIT_CHANNELS)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: EDIT_CHANNELS");
            }

            channelService.reorderChannels(serverId, channelIds);

            WsMessage msg = WsMessage.builder()
                    .type(WsMessageType.CHANNELS_REORDERED)
                    .payload(ChannelsReorderedPayload.builder().channelIds(channelIds).build())
                    .build();

            clientMessageSender.broadcastToServer(serverId, gson.toJson(msg));
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to reorder channels: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    @PostMapping
    public ResponseEntity<?> createChannel(@RequestBody ChannelCreateRequest request) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();

            if (serverId == null || userId == null) {
                return ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Invalid session");
            }

            boolean nameRequired = request.getChannelType() == null
                    || (request.getChannelType() != ChannelType.SPACER
                        && request.getChannelType() != ChannelType.DIVIDER
                        && request.getChannelType() != ChannelType.CLOCK);
            if (nameRequired && (request.getChannelName() == null || request.getChannelName().isBlank())) {
                return ErrorResponse.of(HttpStatus.BAD_REQUEST, "Channel name is required");
            }
            if (nameRequired) {
                String cName = request.getChannelName().trim();
                if (cName.length() > 100) {
                    return ErrorResponse.of(HttpStatus.BAD_REQUEST, "Channel name must be 100 characters or fewer");
                }
                if (containsEmoji(cName)) {
                    return ErrorResponse.of(HttpStatus.BAD_REQUEST, "Channel name must not contain emoji");
                }
            }

            if (!permissionService.has(userId, serverId, Permission.CREATE_CHANNELS)) {
                log.warn("createChannel denied: userId={} on serverId={}", userId, serverId);
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: CREATE_CHANNELS");
            }

            ChannelSummary created = channelService.createChannel(serverId, request);

            ChannelCreatedPayload payload = ChannelCreatedPayload.builder()
                    .channelId(created.getChannelId())
                    .serverId(created.getServerId())
                    .channelName(created.getChannelName())
                    .channelType(created.getChannelType())
                    .description(created.getDescription())
                    .position(created.getPosition())
                    .icon(created.getIcon())
                    .build();

            WsMessage msg = WsMessage.builder()
                    .type(WsMessageType.CHANNEL_CREATED)
                    .payload(payload)
                    .build();

            clientMessageSender.broadcastToServer(serverId, gson.toJson(msg));

            return ResponseEntity.ok(created);
        } catch (Exception e) {
            log.error("Failed to create channel: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    @PutMapping("/{channelId}")
    public ResponseEntity<?> updateChannel(
            @PathVariable UUID channelId,
            @RequestBody ChannelUpdateRequest request) {
        if (request.getChannelName() != null && !request.getChannelName().isBlank()) {
            String cName = request.getChannelName().trim();
            if (cName.length() > 100) {
                return ErrorResponse.of(HttpStatus.BAD_REQUEST, "Channel name must be 100 characters or fewer");
            }
            if (containsEmoji(cName)) {
                return ErrorResponse.of(HttpStatus.BAD_REQUEST, "Channel name must not contain emoji");
            }
        }
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();

            if (!permissionService.has(userId, serverId, Permission.EDIT_CHANNELS)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: EDIT_CHANNELS");
            }

            ChannelSummary updated = channelService.updateChannel(serverId, channelId, request);

            WsMessage msg = WsMessage.builder()
                    .type(WsMessageType.CHANNEL_UPDATED)
                    .payload(ChannelUpdatedPayload.builder()
                            .channelId(updated.getChannelId())
                            .serverId(updated.getServerId())
                            .channelName(updated.getChannelName())
                            .icon(updated.getIcon())
                            .build())
                    .build();

            clientMessageSender.broadcastToServer(serverId, gson.toJson(msg));
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ErrorResponse.of(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update channel: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    @DeleteMapping("/{channelId}")
    public ResponseEntity<?> deleteChannel(@PathVariable UUID channelId) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();

            if (!permissionService.has(userId, serverId, Permission.DELETE_CHANNELS)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: DELETE_CHANNELS");
            }

            if (channelService.getChannelById(serverId, channelId).isEmpty()) {
                return ErrorResponse.of(HttpStatus.NOT_FOUND, "Channel not found");
            }

            permissionService.deleteChannelPermissions(channelId);
            channelService.deleteChannel(channelId);

            // Immediately clean up voice state for the deleted channel so stale
            // registry entries and the LiveKit room don't linger if any clients
            // are offline or fail to send CHANNEL_LEAVE in response.
            Collection<UserSessionEntry> evicted = webrtcRoomsManager.evictChannel(serverId, channelId);
            liveKitTokenService.deleteRoom(serverId, channelId);

            // Notify other server members about stream state changes for any
            // users who were actively streaming or watching in the deleted channel.
            for (UserSessionEntry entry : evicted) {
                UUID evictedUserId = entry.getUserId();
                if (entry.isScreenSharingEnabled()) {
                    webrtcRoomsManager.clearWatchersForStreamer(evictedUserId);
                    String viewerCountMsg = gson.toJson(WsMessage.builder()
                            .type(WsMessageType.STREAM_VIEWER_COUNT)
                            .payload(StreamViewerCountPayload.builder()
                                    .streamerUserId(evictedUserId)
                                    .count(0)
                                    .build())
                            .build());
                    clientMessageSender.broadcastToServer(serverId, viewerCountMsg);
                    clientMessageSender.broadcastToServer(serverId, gson.toJson(WsMessage.builder()
                            .type(WsMessageType.STREAM_ENDED)
                            .payload(StreamEndedPayload.builder()
                                    .streamerUserId(evictedUserId)
                                    .build())
                            .build()));
                }
                webrtcRoomsManager.removeAsWatcher(evictedUserId).forEach((streamerId, count) ->
                        clientMessageSender.broadcastToServer(serverId, gson.toJson(WsMessage.builder()
                                .type(WsMessageType.STREAM_VIEWER_COUNT)
                                .payload(StreamViewerCountPayload.builder()
                                        .streamerUserId(streamerId)
                                        .count(count)
                                        .build())
                                .build())));
            }

            WsMessage msg = WsMessage.builder()
                    .type(WsMessageType.CHANNEL_DELETED)
                    .payload(ChannelDeletedPayload.builder()
                            .channelId(channelId)
                            .serverId(serverId)
                            .build())
                    .build();

            clientMessageSender.broadcastToServer(serverId, gson.toJson(msg));
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Failed to delete channel: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }
}
