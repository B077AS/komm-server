package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.websocket.messages.UserSessionEntry;
import com.kommserver.websocket.senders.ClientMessageSender;
import com.kommserver.websocket.managers.ClientSessionManager;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.UserDeafenedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class UserDeafenedHandler implements ClientInboundMessageHandler {

    private final Gson gson;
    private final ClientMessageSender clientMessageSender;
    private final WebrtcRoomsManager webrtcRoomsManager;

    @Override
    public WsMessageType getType() {
        return WsMessageType.USER_DEAFENED;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        UUID userId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);

        UserDeafenedPayload message = gson.fromJson(payload, UserDeafenedPayload.class);

        WebrtcRoomsManager.ChannelRef room = webrtcRoomsManager.getCurrentRoom(userId);
        if (room == null) return;

        UserSessionEntry entry = webrtcRoomsManager.getEntry(room.serverId(), room.channelId(), userId);

        // Reject self-undeafen while server-deafened: send back a forced deafen
        if (message.isSpeakerEnabled() && entry != null && !entry.isServerSpeakerEnabled()) {
            WsMessage forceDeafen = WsMessage.builder()
                    .type(WsMessageType.USER_DEAFENED)
                    .payload(UserDeafenedPayload.builder().userId(userId).speakerEnabled(false).build())
                    .build();
            try { session.sendMessage(new TextMessage(gson.toJson(forceDeafen))); } catch (Exception ignored) {}
            return;
        }

        webrtcRoomsManager.updateSpeaker(room.serverId(), room.channelId(), userId, message.isSpeakerEnabled());

        message.setUserId(userId);

        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.USER_DEAFENED)
                .payload(message)
                .build();

        clientMessageSender.broadcastToServer(serverId, gson.toJson(msg));
    }
}