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
import com.kommserver.websocket.messages.payloads.UserMutedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class UserMutedHandler implements ClientInboundMessageHandler {

    private final Gson gson;
    private final ClientMessageSender clientMessageSender;
    private final WebrtcRoomsManager webrtcRoomsManager;

    @Override
    public WsMessageType getType() {
        return WsMessageType.USER_MUTED;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        UUID userId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);

        UserMutedPayload message = gson.fromJson(payload, UserMutedPayload.class);

        WebrtcRoomsManager.ChannelRef room = webrtcRoomsManager.getCurrentRoom(userId);
        if (room == null) return;

        UserSessionEntry entry = webrtcRoomsManager.getEntry(room.serverId(), room.channelId(), userId);

        // Reject self-unmute while server-muted: send back a forced mute
        if (message.isMicEnabled() && entry != null && !entry.isServerMicEnabled()) {
            WsMessage forceMute = WsMessage.builder()
                    .type(WsMessageType.USER_MUTED)
                    .payload(UserMutedPayload.builder().userId(userId).micEnabled(false).build())
                    .build();
            try { session.sendMessage(new TextMessage(gson.toJson(forceMute))); } catch (Exception ignored) {}
            return;
        }

        webrtcRoomsManager.updateMic(room.serverId(), room.channelId(), userId, message.isMicEnabled());

        message.setUserId(userId);

        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.USER_MUTED)
                .payload(message)
                .build();

        clientMessageSender.broadcastToServer(serverId, gson.toJson(msg));
    }
}