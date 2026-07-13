package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.dto.request.CustomRoleCreateRequest;
import com.kommserver.model.dto.summary.CustomRoleSummary;
import com.kommserver.service.PermissionService;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.CustomRolePayload;
import com.kommserver.websocket.messages.payloads.PermCreateCustomRoleRequestPayload;
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
public class PermCreateCustomRoleRequestHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final PermissionService permissionService;
    private final HubMessageSender hubMessageSender;
    private final ClientMessageSender clientMessageSender;

    @Override
    public WsMessageType getType() { return WsMessageType.PERM_CREATE_CUSTOM_ROLE_REQUEST; }

    @Override
    public void handle(JsonObject payload) {
        PermCreateCustomRoleRequestPayload req = gson.fromJson(payload, PermCreateCustomRoleRequestPayload.class);
        try {
            CustomRoleCreateRequest createReq = CustomRoleCreateRequest.builder()
                    .roleName(req.getRoleName())
                    .color(req.getColor())
                    .permissions(req.getPermissions())
                    .build();

            CustomRoleSummary created = permissionService.createCustomRole(req.getServerId(), req.getUserId(), createReq);
            broadcastCustomRoleCreated(req.getServerId(), created);

            hubMessageSender.send(WsMessageType.PERM_CREATE_CUSTOM_ROLE_RESPONSE, PermProxyResponsePayload.builder()
                    .correlationId(req.getCorrelationId())
                    .success(true)
                    .data(gson.toJsonTree(created))
                    .build());
        } catch (Exception e) {
            log.error("PERM_CREATE_CUSTOM_ROLE_REQUEST failed: {}", e.getMessage());
            hubMessageSender.send(WsMessageType.PERM_CREATE_CUSTOM_ROLE_RESPONSE, PermProxyResponsePayload.builder()
                    .correlationId(req.getCorrelationId()).success(false).error(e.getMessage()).build());
        }
    }

    private void broadcastCustomRoleCreated(UUID serverId, CustomRoleSummary cr) {
        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.CUSTOM_ROLE_CREATED)
                .payload(CustomRolePayload.builder()
                        .roleId(cr.getRoleId()).serverId(serverId).roleName(cr.getRoleName())
                        .color(cr.getColor()).permissions(cr.getPermissions()).position(cr.getPosition())
                        .build())
                .build();
        clientMessageSender.broadcastToServer(serverId, gson.toJson(msg));
    }
}
