package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.repository.ServerMemberRepository;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.ForceDisconnectPayload;
import com.kommserver.websocket.messages.payloads.KickUserNotificationPayload;
import com.kommserver.websocket.messages.payloads.UserKickedPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class KickUserNotificationHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final ServerMemberRepository serverMemberRepository;
    private final ClientMessageSender clientMessageSender;

    @Override
    public WsMessageType getType() { return WsMessageType.KICK_USER_NOTIFICATION; }

    @Override
    @Transactional
    public void handle(JsonObject payload) {
        KickUserNotificationPayload p = gson.fromJson(payload, KickUserNotificationPayload.class);
        UUID serverId = p.getServerId();
        UUID targetUserId = p.getTargetUserId();
        UUID requesterId = p.getRequesterId();

        serverMemberRepository.findByServerIdAndUserId(serverId, targetUserId)
                .ifPresent(serverMemberRepository::delete);

        String forceMsg = gson.toJson(WsMessage.builder()
                .type(WsMessageType.FORCE_DISCONNECT)
                .payload(gson.toJsonTree(ForceDisconnectPayload.builder()
                        .reason("KICKED")
                        .build()).getAsJsonObject())
                .build());
        clientMessageSender.sendToUser(serverId, targetUserId, forceMsg);
        clientMessageSender.closeUserSession(serverId, targetUserId);

        String broadcastMsg = gson.toJson(WsMessage.builder()
                .type(WsMessageType.USER_KICKED)
                .payload(gson.toJsonTree(UserKickedPayload.builder()
                        .userId(targetUserId)
                        .kickedByUserId(requesterId)
                        .build()).getAsJsonObject())
                .build());
        clientMessageSender.broadcastToServerExcept(serverId, targetUserId, broadcastMsg);

        log.info("KICK_USER_NOTIFICATION: requesterId={} kicked userId={} from serverId={}", requesterId, targetUserId, serverId);
    }
}
