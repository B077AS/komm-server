package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.repository.ServerBanRepository;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.UnbanUserNotificationPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class UnbanUserNotificationHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final ServerBanRepository serverBanRepository;

    @Override
    public WsMessageType getType() { return WsMessageType.UNBAN_USER_NOTIFICATION; }

    @Override
    @Transactional
    public void handle(JsonObject payload) {
        UnbanUserNotificationPayload p = gson.fromJson(payload, UnbanUserNotificationPayload.class);
        serverBanRepository.deleteByServerIdAndUserId(p.getServerId(), p.getTargetUserId());
        log.info("UNBAN_USER_NOTIFICATION: userId={} unbanned from serverId={}", p.getTargetUserId(), p.getServerId());
    }
}
