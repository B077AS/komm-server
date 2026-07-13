package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.service.PermissionService;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.CustomRolePayload;
import com.kommserver.websocket.messages.payloads.PermDeleteCustomRoleRequestPayload;
import com.kommserver.websocket.messages.payloads.PermProxyResponsePayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import com.kommserver.websocket.senders.HubMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PermDeleteCustomRoleRequestHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final PermissionService permissionService;
    private final HubMessageSender hubMessageSender;
    private final ClientMessageSender clientMessageSender;

    @Override
    public WsMessageType getType() { return WsMessageType.PERM_DELETE_CUSTOM_ROLE_REQUEST; }

    @Override
    public void handle(JsonObject payload) {
        PermDeleteCustomRoleRequestPayload req = gson.fromJson(payload, PermDeleteCustomRoleRequestPayload.class);
        try {
            permissionService.deleteCustomRole(req.getServerId(), req.getUserId(), req.getRoleId());
            broadcastCustomRoleDeleted(req.getServerId(), req.getRoleId());
            hubMessageSender.send(WsMessageType.PERM_DELETE_CUSTOM_ROLE_RESPONSE, PermProxyResponsePayload.builder()
                    .correlationId(req.getCorrelationId()).success(true).build());
        } catch (Exception e) {
            log.error("PERM_DELETE_CUSTOM_ROLE_REQUEST failed: {}", e.getMessage());
            hubMessageSender.send(WsMessageType.PERM_DELETE_CUSTOM_ROLE_RESPONSE, PermProxyResponsePayload.builder()
                    .correlationId(req.getCorrelationId()).success(false).error(e.getMessage()).build());
        }
    }

    private void broadcastCustomRoleDeleted(UUID serverId, UUID roleId) {
        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.CUSTOM_ROLE_DELETED)
                .payload(CustomRolePayload.builder().roleId(roleId).serverId(serverId).build())
                .build();
        clientMessageSender.broadcastToServer(serverId, gson.toJson(msg));
    }
}
