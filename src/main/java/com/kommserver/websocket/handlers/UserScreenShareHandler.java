package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.Permission;
import com.kommserver.service.PermissionService;
import com.kommserver.websocket.senders.ClientMessageSender;
import com.kommserver.websocket.managers.ClientSessionManager;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.StreamEndedPayload;
import com.kommserver.websocket.messages.payloads.StreamViewerCountPayload;
import com.kommserver.websocket.messages.payloads.UserScreenSharePayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserScreenShareHandler implements ClientInboundMessageHandler {

    private final Gson gson;
    private final ClientMessageSender clientMessageSender;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final PermissionService permissionService;

    @Override
    public WsMessageType getType() {
        return WsMessageType.USER_SCREEN_SHARE;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        UUID userId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);

        UserScreenSharePayload message = gson.fromJson(payload, UserScreenSharePayload.class);

        WebrtcRoomsManager.ChannelRef room = webrtcRoomsManager.getCurrentRoom(userId);
        if (room == null) return;

        if (message.isSharing()
                && !permissionService.hasInChannel(userId, serverId, room.channelId(), Permission.SCREEN_SHARE)) {
            log.warn("USER_SCREEN_SHARE denied: userId={} missing SCREEN_SHARE in channelId={}", userId, room.channelId());
            return;
        }

        webrtcRoomsManager.updateScreenSharing(room.serverId(), room.channelId(), userId, message.isSharing());

        if (!message.isSharing()) {
            webrtcRoomsManager.clearWatchersForStreamer(userId);
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

        message.setUserId(userId);

        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.USER_SCREEN_SHARE)
                .payload(message)
                .build();

        clientMessageSender.broadcastToServer(serverId, gson.toJson(msg));
    }

}
