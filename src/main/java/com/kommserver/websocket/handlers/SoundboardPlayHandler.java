package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.Permission;
import com.kommserver.service.PermissionService;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.managers.ClientSessionManager;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.SoundboardPlayPayload;
import com.kommserver.websocket.messages.payloads.SoundboardPlayingPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

/**
 * Handles a soundboard trigger: validates the caller is in a voice channel with
 * USE_SOUNDBOARD, then broadcasts SOUNDBOARD_PLAYING to everyone in that channel.
 * Server sounds are stored in the DB; personal sounds are client-local and have
 * no server record — both are allowed as long as the permission check passes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SoundboardPlayHandler implements ClientInboundMessageHandler {

    private final Gson gson;
    private final PermissionService permissionService;
    private final WebrtcRoomsManager webrtcRoomsManager;

    @Override
    public WsMessageType getType() { return WsMessageType.SOUNDBOARD_PLAY; }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        UUID userId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);
        SoundboardPlayPayload req = gson.fromJson(payload, SoundboardPlayPayload.class);
        if (req == null || req.getSoundboardId() == null) return;

        WebrtcRoomsManager.ChannelRef room = webrtcRoomsManager.getCurrentRoomInServer(serverId, userId);
        if (room == null) {
            log.debug("[Soundboard] PLAY ignored: userId={} not in a voice channel", userId);
            return;
        }

        if (!permissionService.hasInChannel(userId, serverId, room.channelId(), Permission.USE_SOUNDBOARD)) {
            log.warn("[Soundboard] PLAY denied: userId={} missing USE_SOUNDBOARD", userId);
            return;
        }

        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.SOUNDBOARD_PLAYING)
                .payload(SoundboardPlayingPayload.builder()
                        .soundboardId(req.getSoundboardId())
                        .userId(userId)
                        .build())
                .build();
        webrtcRoomsManager.broadcastToChannel(serverId, room.channelId(), msg);
        webrtcRoomsManager.recordSoundboardPlay(room.channelId(), userId, req.getSoundboardId());
    }
}
