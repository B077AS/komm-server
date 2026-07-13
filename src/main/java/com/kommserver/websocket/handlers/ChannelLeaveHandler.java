package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.Permission;
import com.kommserver.service.PermissionService;
import com.kommserver.sfu.LiveKitTokenService;
import com.kommserver.websocket.senders.ClientMessageSender;
import com.kommserver.websocket.managers.ClientSessionManager;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.UserSessionEntry;
import com.kommserver.websocket.messages.payloads.ChannelDeletedPayload;
import com.kommserver.websocket.messages.payloads.StreamEndedPayload;
import com.kommserver.websocket.messages.payloads.StreamViewerCountPayload;
import com.kommserver.websocket.messages.payloads.UserLeftChannelPayload;
import com.kommserver.websocket.messages.payloads.UserScreenSharePayload;
import com.kommserver.websocket.messages.payloads.ChannelLeavePayload;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelLeaveHandler implements ClientInboundMessageHandler {

    private final Gson gson;
    private final LiveKitTokenService liveKitTokenService;
    private final ClientMessageSender clientMessageSender;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final PermissionService permissionService;

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNEL_LEAVE;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        UUID userId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);

        if (!session.isOpen()) {
            log.warn("CHANNEL_LEAVE: session already closed, skipping. userId={}", userId);
            return;
        }

        ChannelLeavePayload leavePayload = gson.fromJson(payload, ChannelLeavePayload.class);
        UUID channelId = leavePayload.getChannelId();

        if (channelId == null) {
            log.warn("CHANNEL_LEAVE: missing channelId in payload, skipping. userId={}", userId);
            return;
        }

        UserSessionEntry entry = webrtcRoomsManager.getEntry(serverId, channelId, userId);
        boolean wasStreaming = entry != null && entry.isScreenSharingEnabled();

        boolean roomEmpty = webrtcRoomsManager.leave(serverId, channelId, userId);

        Map<UUID, Integer> watchUpdates = webrtcRoomsManager.removeAsWatcher(userId);
        watchUpdates.forEach((streamerId, count) ->
                clientMessageSender.broadcastToServer(serverId, gson.toJson(
                        WsMessage.builder()
                                .type(WsMessageType.STREAM_VIEWER_COUNT)
                                .payload(StreamViewerCountPayload.builder()
                                        .streamerUserId(streamerId)
                                        .count(count)
                                        .build())
                                .build())));

        if (wasStreaming) {
            webrtcRoomsManager.clearWatchersForStreamer(userId);
            // Clear the live indicator on the streamer's card for every client. Leaving the
            // channel disposes the screen share locally without emitting USER_SCREEN_SHARE,
            // so the server must broadcast it explicitly (matches ChannelJoinHandler's
            // forced-leave path used by MOVE_MEMBER).
            clientMessageSender.broadcastToServer(serverId, gson.toJson(
                    WsMessage.builder()
                            .type(WsMessageType.USER_SCREEN_SHARE)
                            .payload(UserScreenSharePayload.builder()
                                    .userId(userId)
                                    .sharing(false)
                                    .audioEnabled(false)
                                    .build())
                            .build()));
            clientMessageSender.broadcastToServer(serverId, gson.toJson(
                    WsMessage.builder()
                            .type(WsMessageType.STREAM_VIEWER_COUNT)
                            .payload(StreamViewerCountPayload.builder()
                                    .streamerUserId(userId)
                                    .count(0)
                                    .build())
                            .build()));
            clientMessageSender.broadcastToServer(serverId, gson.toJson(
                    WsMessage.builder()
                            .type(WsMessageType.STREAM_ENDED)
                            .payload(StreamEndedPayload.builder()
                                    .streamerUserId(userId)
                                    .build())
                            .build()));
        }

        // If the user only saw this channel because they were moved here,
        // hide it again now that they've left voice.
        if (!permissionService.hasInChannel(userId, serverId, channelId, Permission.VIEW_CHANNEL)) {
            WsMessage hide = WsMessage.builder()
                    .type(WsMessageType.CHANNEL_DELETED)
                    .payload(gson.toJsonTree(ChannelDeletedPayload.builder()
                            .channelId(channelId)
                            .serverId(serverId)
                            .build()).getAsJsonObject())
                    .build();
            clientMessageSender.sendToUser(serverId, userId, gson.toJson(hide));
        }

        if (roomEmpty) {
            liveKitTokenService.deleteRoom(serverId, channelId);
            log.info("CHANNEL_LEAVE: last user left, room deleted. serverId={} channelId={}", serverId, channelId);
        } else {
            log.info("CHANNEL_LEAVE: userId={} left serverId={} channelId={}", userId, serverId, channelId);
        }

        // Broadcast to everyone connected to this server
        WsMessage broadcast = WsMessage.builder()
                .type(WsMessageType.USER_LEFT_CHANNEL)
                .payload(gson.toJsonTree(UserLeftChannelPayload.builder()
                        .channelId(channelId)
                        .userId(userId)
                        .build()).getAsJsonObject())
                .build();

        clientMessageSender.broadcastToServer(serverId, gson.toJson(broadcast));
    }
}