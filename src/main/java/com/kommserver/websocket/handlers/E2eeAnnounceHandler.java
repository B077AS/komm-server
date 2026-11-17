package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.managers.ClientSessionManager;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.messages.UserSessionEntry;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.E2eeAnnouncePayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

/**
 * Relays a member's ephemeral E2EE public key to everyone else in the voice
 * channel the member is currently in. The sender and channel are taken from
 * the session, never from the payload.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class E2eeAnnounceHandler implements ClientInboundMessageHandler {

    /** An X.509-encoded X25519 key is 44 bytes (60 in base64); leave room, refuse junk. */
    static final int MAX_PUBLIC_KEY_CHARS = 256;

    private final Gson gson;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final ClientMessageSender clientMessageSender;

    @Override
    public WsMessageType getType() {
        return WsMessageType.E2EE_ANNOUNCE;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        UUID userId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);

        WebrtcRoomsManager.ChannelRef room = webrtcRoomsManager.getCurrentRoomInServer(serverId, userId);
        if (room == null) {
            log.debug("E2EE_ANNOUNCE ignored: userId={} is not in a voice channel", userId);
            return;
        }

        E2eeAnnouncePayload in = gson.fromJson(payload, E2eeAnnouncePayload.class);
        String publicKey = in.getPublicKey();
        if (publicKey == null || publicKey.isEmpty() || publicKey.length() > MAX_PUBLIC_KEY_CHARS) {
            log.warn("E2EE_ANNOUNCE rejected: bad public key from userId={}", userId);
            return;
        }

        String json = gson.toJson(WsMessage.builder()
                .type(WsMessageType.E2EE_ANNOUNCE)
                .payload(gson.toJsonTree(E2eeAnnouncePayload.builder()
                        .channelId(room.channelId())
                        .userId(userId)
                        .publicKey(publicKey)
                        .build()).getAsJsonObject())
                .build());

        for (UserSessionEntry entry : webrtcRoomsManager.getEntriesInChannel(serverId, room.channelId())) {
            if (!entry.getUserId().equals(userId)) {
                clientMessageSender.sendToUser(serverId, entry.getUserId(), json);
            }
        }
    }
}
