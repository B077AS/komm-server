package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.managers.ClientSessionManager;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.E2eeKeyPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

/**
 * Relays one member's sealed media key to one other member of the same voice
 * channel. The key is encrypted to the recipient's ephemeral key, so this only
 * routes it: it checks that both are in the same channel and stamps the sender.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class E2eeKeyHandler implements ClientInboundMessageHandler {

    /** Sealed 32-byte key + GCM tag is 64 base64 chars; nonce 16. Refuse anything far bigger. */
    private static final int MAX_FIELD_CHARS = 512;

    private final Gson gson;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final ClientMessageSender clientMessageSender;

    @Override
    public WsMessageType getType() {
        return WsMessageType.E2EE_KEY;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        UUID userId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);

        WebrtcRoomsManager.ChannelRef room = webrtcRoomsManager.getCurrentRoomInServer(serverId, userId);
        if (room == null) return;

        E2eeKeyPayload in = gson.fromJson(payload, E2eeKeyPayload.class);
        UUID to = in.getToUserId();
        if (to == null || to.equals(userId)
                || !webrtcRoomsManager.isUserInChannel(serverId, room.channelId(), to)) {
            log.debug("E2EE_KEY dropped: userId={} -> {} not in the same voice channel", userId, to);
            return;
        }
        if (!fits(in.getPublicKey(), E2eeAnnounceHandler.MAX_PUBLIC_KEY_CHARS)
                || !fits(in.getNonce(), MAX_FIELD_CHARS) || !fits(in.getSealedKey(), MAX_FIELD_CHARS)
                || in.getGeneration() < 0 || in.getGeneration() > 255
                || in.getParticipantId() < 0 || in.getParticipantId() > 0xFFFF_FFFFL) {
            log.warn("E2EE_KEY rejected: malformed payload from userId={}", userId);
            return;
        }

        in.setChannelId(room.channelId());
        in.setFromUserId(userId);

        clientMessageSender.sendToUser(serverId, to, gson.toJson(WsMessage.builder()
                .type(WsMessageType.E2EE_KEY)
                .payload(gson.toJsonTree(in).getAsJsonObject())
                .build()));
    }

    private static boolean fits(String s, int max) {
        return s != null && !s.isEmpty() && s.length() <= max;
    }
}
