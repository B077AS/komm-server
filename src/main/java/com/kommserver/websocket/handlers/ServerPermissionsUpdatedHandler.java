package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.ServerMember;
import com.kommserver.model.db.ServerRolePermission;
import com.kommserver.repository.ServerRolePermissionRepository;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.ServerPermissionsUpdatedPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ServerPermissionsUpdatedHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final ServerRolePermissionRepository rolePermissionRepository;
    private final ClientMessageSender clientMessageSender;

    @Override
    public WsMessageType getType() {
        return WsMessageType.SERVER_PERMISSIONS_UPDATED;
    }

    @Override
    public void handle(JsonObject payload) {
        ServerPermissionsUpdatedPayload p = gson.fromJson(payload, ServerPermissionsUpdatedPayload.class);
        UUID serverId = p.getServerId();
        Map<String, List<String>> rolePerms = p.getRolePermissions();
        if (rolePerms == null || serverId == null) return;

        rolePerms.forEach((roleName, permissions) -> {
            try {
                ServerMember.Role role = ServerMember.Role.valueOf(roleName);
                ServerRolePermission.ServerRolePermissionId id =
                        new ServerRolePermission.ServerRolePermissionId(serverId, role);
                ServerRolePermission srp = rolePermissionRepository.findById(id)
                        .orElse(ServerRolePermission.builder().serverId(serverId).role(role).build());
                srp.setPermissions(permissions);
                rolePermissionRepository.save(srp);
            } catch (IllegalArgumentException e) {
                log.warn("SERVER_PERMISSIONS_UPDATED: unknown role '{}'", roleName);
            }
        });

        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.SERVER_PERMISSIONS_UPDATED)
                .payload(p)
                .build();
        clientMessageSender.broadcastToServer(serverId, gson.toJson(msg));
        log.info("SERVER_PERMISSIONS_UPDATED applied and forwarded for serverId={}", serverId);
    }
}
