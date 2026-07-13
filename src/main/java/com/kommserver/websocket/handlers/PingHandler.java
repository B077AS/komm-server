package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.managers.ClientSessionManager;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.PingPayload;
import com.kommserver.websocket.service.UserPingTrackerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PingHandler implements ClientInboundMessageHandler {

    private final Gson gson;
    private final UserPingTrackerService pingTrackerService;

    @Override
    public WsMessageType getType() {
        return WsMessageType.PING;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        PingPayload ping = gson.fromJson(payload, PingPayload.class);

        // ── Echo timestamp back so the client can measure its own RTT ─────────
        WsMessage pong = WsMessage.builder()
                .type(WsMessageType.PONG)
                .payload(gson.toJsonTree(PingPayload.builder()
                        .timestamp(ping.getTimestamp())
                        .build()).getAsJsonObject())
                .build();

        try {
            session.sendMessage(new TextMessage(gson.toJson(pong)));
        } catch (Exception e) {
            log.error("Failed to send PONG to session={}", session.getId(), e);
        }

        // ── Track self-reported RTT/loss for ping observers ────────────────────
        if (ping.getLastRttMs() != null) {
            UUID userId   = ClientSessionManager.userId(session);
            UUID serverId = ClientSessionManager.serverId(session);
            if (userId != null && serverId != null) {
                int lossPct = ping.getLastLossPct() != null ? ping.getLastLossPct() : 0;
                pingTrackerService.record(serverId, userId, ping.getLastRttMs(), lossPct);
            }
        }
    }
}
