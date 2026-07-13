package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.ServerMember;
import com.kommserver.service.PermissionService;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.PermProxyResponsePayload;
import com.kommserver.websocket.messages.payloads.PermUpdateRoleRequestPayload;
import com.kommserver.websocket.messages.payloads.ServerPermissionsUpdatedPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import com.kommserver.websocket.senders.HubMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PermUpdateRoleRequestHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final PermissionService permissionService;
    private final HubMessageSender hubMessageSender;
    private final ClientMessageSender clientMessageSender;

    @Override
    public WsMessageType getType() { return WsMessageType.PERM_UPDATE_ROLE_REQUEST; }

    @Override
    public void handle(JsonObject payload) {
        PermUpdateRoleRequestPayload req = gson.fromJson(payload, PermUpdateRoleRequestPayload.class);
        try {
            ServerMember.Role role = ServerMember.Role.valueOf(req.getRole().toUpperCase());
            permissionService.updateRolePermission(req.getServerId(), req.getUserId(), role, req.getPermissions());
            broadcastPermissionsUpdated(req.getServerId());
            hubMessageSender.send(WsMessageType.PERM_UPDATE_ROLE_RESPONSE, PermProxyResponsePayload.builder()
                    .correlationId(req.getCorrelationId()).success(true).build());
        } catch (Exception e) {
            log.error("PERM_UPDATE_ROLE_REQUEST failed: {}", e.getMessage());
            hubMessageSender.send(WsMessageType.PERM_UPDATE_ROLE_RESPONSE, PermProxyResponsePayload.builder()
                    .correlationId(req.getCorrelationId()).success(false).error(e.getMessage()).build());
        }
    }

    private void broadcastPermissionsUpdated(java.util.UUID serverId) {
        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.SERVER_PERMISSIONS_UPDATED)
                .payload(ServerPermissionsUpdatedPayload.builder()
                        .serverId(serverId)
                        .rolePermissions(permissionService.getServerRolePermissions(serverId))
                        .build())
                .build();
        clientMessageSender.broadcastToServer(serverId, gson.toJson(msg));
    }
}
