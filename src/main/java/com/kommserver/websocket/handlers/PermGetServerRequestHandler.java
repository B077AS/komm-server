package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.dto.summary.ServerPermissionsSummary;
import com.kommserver.service.PermissionService;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.PermGetServerRequestPayload;
import com.kommserver.websocket.messages.payloads.PermProxyResponsePayload;
import com.kommserver.websocket.senders.HubMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PermGetServerRequestHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final PermissionService permissionService;
    private final HubMessageSender hubMessageSender;

    @Override
    public WsMessageType getType() { return WsMessageType.PERM_GET_SERVER_REQUEST; }

    @Override
    public void handle(JsonObject payload) {
        PermGetServerRequestPayload req = gson.fromJson(payload, PermGetServerRequestPayload.class);
        try {
            ServerPermissionsSummary summary = permissionService.getServerPermissions(req.getServerId(), req.getUserId());
            hubMessageSender.send(WsMessageType.PERM_GET_SERVER_RESPONSE, PermProxyResponsePayload.builder()
                    .correlationId(req.getCorrelationId())
                    .success(true)
                    .data(gson.toJsonTree(summary))
                    .build());
        } catch (Exception e) {
            log.error("PERM_GET_SERVER_REQUEST failed: {}", e.getMessage());
            hubMessageSender.send(WsMessageType.PERM_GET_SERVER_RESPONSE, PermProxyResponsePayload.builder()
                    .correlationId(req.getCorrelationId())
                    .success(false)
                    .error(e.getMessage())
                    .build());
        }
    }
}
