package com.kommserver.websocket.handlers;

import com.google.gson.JsonObject;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.managers.ClientSessionManager;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.SoundboardStoppedPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

/**
 * A member asks to stop the sounds they triggered (e.g. a long clip). Broadcasts
 * SOUNDBOARD_STOPPED to the voice channel so every client halts that member's sounds.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SoundboardStopHandler implements ClientInboundMessageHandler {

    private final WebrtcRoomsManager webrtcRoomsManager;

    @Override
    public WsMessageType getType() { return WsMessageType.SOUNDBOARD_STOP; }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        UUID userId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);

        WebrtcRoomsManager.ChannelRef room = webrtcRoomsManager.getCurrentRoomInServer(serverId, userId);
        if (room == null) return;

        webrtcRoomsManager.clearSoundboardPlay(room.channelId(), userId);

        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.SOUNDBOARD_STOPPED)
                .payload(SoundboardStoppedPayload.builder().userId(userId).build())
                .build();
        webrtcRoomsManager.broadcastToChannel(serverId, room.channelId(), msg);
    }
}
