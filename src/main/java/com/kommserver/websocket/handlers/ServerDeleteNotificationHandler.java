package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.service.ServerDeletionService;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.ServerDeletedPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Hub → installation: the hub (source of truth) has flagged a server for deletion. Flag it locally
 * and disconnect connected members; the scheduled purge then deletes the data and acks the hub.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServerDeleteNotificationHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final ServerDeletionService serverDeletionService;

    @Override
    public WsMessageType getType() { return WsMessageType.SERVER_DELETE_NOTIFICATION; }

    @Override
    public void handle(JsonObject payload) {
        ServerDeletedPayload p = gson.fromJson(payload, ServerDeletedPayload.class);
        if (p == null || p.getServerId() == null) return;
        serverDeletionService.markForDeletion(p.getServerId());
    }
}
