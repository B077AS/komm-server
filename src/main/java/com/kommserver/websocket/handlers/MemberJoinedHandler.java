package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.ServerMember;
import com.kommserver.repository.ServerMemberRepository;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.MemberJoinedPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemberJoinedHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final ServerMemberRepository serverMemberRepository;
    private final ClientMessageSender clientMessageSender;

    @Override
    public WsMessageType getType() {
        return WsMessageType.MEMBER_JOINED;
    }

    @Override
    public void handle(JsonObject payload) {
        MemberJoinedPayload data = gson.fromJson(payload, MemberJoinedPayload.class);

        if (serverMemberRepository.existsByServerIdAndUserId(data.getServerId(), data.getUserId())) {
            log.debug("MEMBER_JOINED: userId={} already exists in server={}, skipping", data.getUserId(), data.getServerId());
            return;
        }

        ServerMember member = ServerMember.builder()
                .serverId(data.getServerId())
                .userId(data.getUserId())
                .role(data.getRole() != null ? data.getRole() : ServerMember.Role.MEMBER)
                .joinedAt(data.getJoinedAt())
                .build();

        serverMemberRepository.save(member);
        log.info("MEMBER_JOINED: added userId={} to server={}", data.getUserId(), data.getServerId());

        MemberJoinedPayload clientPayload = MemberJoinedPayload.builder()
                .serverId(data.getServerId())
                .userId(data.getUserId())
                .username(data.getUsername())
                .role(member.getRole())
                .joinedAt(data.getJoinedAt())
                .build();
        String broadcast = gson.toJson(WsMessage.builder()
                .type(WsMessageType.MEMBER_JOINED)
                .payload(gson.toJsonTree(clientPayload).getAsJsonObject())
                .build());
        clientMessageSender.broadcastToServer(data.getServerId(), broadcast);
    }
}
