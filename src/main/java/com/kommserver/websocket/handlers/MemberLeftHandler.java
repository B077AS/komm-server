package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.repository.ServerMemberRepository;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.MemberLeftPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemberLeftHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final ServerMemberRepository serverMemberRepository;
    private final ClientMessageSender clientMessageSender;

    @Override
    public WsMessageType getType() {
        return WsMessageType.MEMBER_LEFT;
    }

    @Override
    @Transactional
    public void handle(JsonObject payload) {
        MemberLeftPayload data = gson.fromJson(payload, MemberLeftPayload.class);

        serverMemberRepository.findByServerIdAndUserId(data.getServerId(), data.getUserId())
                .ifPresent(serverMemberRepository::delete);

        log.info("MEMBER_LEFT: userId={} left server={}", data.getUserId(), data.getServerId());

        String broadcast = gson.toJson(WsMessage.builder()
                .type(WsMessageType.MEMBER_LEFT)
                .payload(gson.toJsonTree(data).getAsJsonObject())
                .build());
        clientMessageSender.broadcastToServerExcept(data.getServerId(), data.getUserId(), broadcast);
    }
}
