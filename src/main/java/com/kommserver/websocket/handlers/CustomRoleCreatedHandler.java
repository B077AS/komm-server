package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.ServerCustomRole;
import com.kommserver.model.db.ServerMember;
import com.kommserver.repository.ServerCustomRoleRepository;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.CustomRolePayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomRoleCreatedHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final ServerCustomRoleRepository customRoleRepo;
    private final ClientMessageSender sender;

    @Override
    public WsMessageType getType() {
        return WsMessageType.CUSTOM_ROLE_CREATED;
    }

    @Override
    public void handle(JsonObject payload) {
        CustomRolePayload p = gson.fromJson(payload, CustomRolePayload.class);
        if (p.getRoleId() == null) return;

        ServerCustomRole entity = customRoleRepo.findById(p.getRoleId())
                .orElse(ServerCustomRole.builder().roleId(p.getRoleId()).build());
        entity.setServerId(p.getServerId());
        entity.setRoleName(p.getRoleName());
        entity.setColor(p.getColor());
        entity.setPermissions(p.getPermissions());
        entity.setPosition(p.getPosition());
        if (p.getBaseRole() != null) {
            try {
                entity.setBaseRole(ServerMember.Role.valueOf(p.getBaseRole()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        customRoleRepo.save(entity);

        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.CUSTOM_ROLE_CREATED)
                .payload(payload)
                .build();
        sender.broadcastToServer(p.getServerId(), gson.toJson(msg));
    }
}