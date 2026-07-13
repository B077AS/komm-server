package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.ServerMemberCustomRole;
import com.kommserver.repository.ServerMemberCustomRoleRepository;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.CustomRoleMemberPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomRoleMemberRemovedHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final ServerMemberCustomRoleRepository memberCustomRoleRepo;
    private final ClientMessageSender sender;

    @Override
    public WsMessageType getType() {
        return WsMessageType.CUSTOM_ROLE_MEMBER_REMOVED;
    }

    @Override
    public void handle(JsonObject payload) {
        CustomRoleMemberPayload p = gson.fromJson(payload, CustomRoleMemberPayload.class);

        memberCustomRoleRepo.deleteById(new ServerMemberCustomRole.ServerMemberCustomRoleId(
                p.getTargetUserId(), p.getServerId(), p.getRoleId()));

        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.CUSTOM_ROLE_MEMBER_REMOVED)
                .payload(payload)
                .build();
        sender.broadcastToServer(p.getServerId(), gson.toJson(msg));
    }
}