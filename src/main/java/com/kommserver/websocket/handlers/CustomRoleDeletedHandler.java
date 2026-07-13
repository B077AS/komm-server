package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.ServerCustomRole;
import com.kommserver.model.db.ServerMember;
import com.kommserver.repository.ServerCustomRoleRepository;
import com.kommserver.repository.ServerMemberCustomRoleRepository;
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
public class CustomRoleDeletedHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final ServerCustomRoleRepository customRoleRepo;
    private final ServerMemberCustomRoleRepository memberCustomRoleRepo;
    private final ClientMessageSender sender;

    @Override
    public WsMessageType getType() {
        return WsMessageType.CUSTOM_ROLE_DELETED;
    }

    @Override
    public void handle(JsonObject payload) {
        CustomRolePayload p = gson.fromJson(payload, CustomRolePayload.class);
        if (p.getRoleId() == null) return;

        memberCustomRoleRepo.deleteByRoleId(p.getRoleId());
        customRoleRepo.deleteById(p.getRoleId());

        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.CUSTOM_ROLE_DELETED)
                .payload(payload)
                .build();
        sender.broadcastToServer(p.getServerId(), gson.toJson(msg));
    }
}