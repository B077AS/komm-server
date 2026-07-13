package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.managers.ClientSessionManager;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.UserPingRequestPayload;
import com.kommserver.websocket.service.UserPingTrackerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserPingUnsubscribeHandler implements ClientInboundMessageHandler {

    private final Gson gson;
    private final UserPingTrackerService pingProbeService;

    @Override
    public WsMessageType getType() {
        return WsMessageType.USER_PING_UNSUBSCRIBE;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        UUID observerId = ClientSessionManager.userId(session);
        UUID serverId   = ClientSessionManager.serverId(session);
        if (observerId == null || serverId == null) return;

        UserPingRequestPayload req = gson.fromJson(payload, UserPingRequestPayload.class);
        UUID targetUserId = req.getTargetUserId();
        if (targetUserId == null) return;

        log.debug("USER_PING_UNSUBSCRIBE: observer={} target={} server={}", observerId, targetUserId, serverId);
        pingProbeService.unsubscribe(serverId, observerId, targetUserId);
    }
}
