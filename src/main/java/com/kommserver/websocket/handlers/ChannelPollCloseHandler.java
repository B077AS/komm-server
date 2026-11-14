package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.Message;
import com.kommserver.service.PollService;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.managers.ClientSessionManager;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.PollClosePayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ChannelPollCloseHandler implements ClientInboundMessageHandler {

    private final Gson gson;
    private final PollService pollService;

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNEL_POLL_CLOSE;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        UUID userId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);
        PollClosePayload close = gson.fromJson(payload, PollClosePayload.class);

        Message message = pollService.close(userId, serverId, close.getMessageId());
        if (message != null) pollService.broadcastUpdate(message, null);
    }
}
