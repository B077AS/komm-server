package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.Permission;
import com.kommserver.service.PermissionService;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.PermCheckPermissionRequestPayload;
import com.kommserver.websocket.messages.payloads.PermProxyResponsePayload;
import com.kommserver.websocket.senders.HubMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PermCheckPermissionRequestHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final PermissionService permissionService;
    private final HubMessageSender hubMessageSender;

    @Override
    public WsMessageType getType() { return WsMessageType.PERM_CHECK_PERMISSION_REQUEST; }

    @Override
    public void handle(JsonObject payload) {
        PermCheckPermissionRequestPayload req = gson.fromJson(payload, PermCheckPermissionRequestPayload.class);
        try {
            Permission permission = Permission.valueOf(req.getPermission());
            boolean result = permissionService.has(req.getUserId(), req.getServerId(), permission);
            hubMessageSender.send(WsMessageType.PERM_CHECK_PERMISSION_RESPONSE, PermProxyResponsePayload.builder()
                    .correlationId(req.getCorrelationId())
                    .success(true)
                    .data(gson.toJsonTree(result))
                    .build());
        } catch (IllegalArgumentException e) {
            hubMessageSender.send(WsMessageType.PERM_CHECK_PERMISSION_RESPONSE, PermProxyResponsePayload.builder()
                    .correlationId(req.getCorrelationId())
                    .success(false)
                    .error("Unknown permission: " + req.getPermission())
                    .build());
        } catch (Exception e) {
            log.error("PERM_CHECK_PERMISSION_REQUEST failed: {}", e.getMessage());
            hubMessageSender.send(WsMessageType.PERM_CHECK_PERMISSION_RESPONSE, PermProxyResponsePayload.builder()
                    .correlationId(req.getCorrelationId())
                    .success(false)
                    .error(e.getMessage())
                    .build());
        }
    }
}
