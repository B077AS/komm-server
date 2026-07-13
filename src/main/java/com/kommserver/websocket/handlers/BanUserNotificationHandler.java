package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.ServerBan;
import com.kommserver.repository.ServerBanRepository;
import com.kommserver.repository.ServerMemberRepository;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.BanUserNotificationPayload;
import com.kommserver.websocket.messages.payloads.ForceDisconnectPayload;
import com.kommserver.websocket.messages.payloads.UserBannedPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class BanUserNotificationHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final ServerBanRepository serverBanRepository;
    private final ServerMemberRepository serverMemberRepository;
    private final ClientMessageSender clientMessageSender;

    @Override
    public WsMessageType getType() { return WsMessageType.BAN_USER_NOTIFICATION; }

    @Override
    @Transactional
    public void handle(JsonObject payload) {
        BanUserNotificationPayload p = gson.fromJson(payload, BanUserNotificationPayload.class);
        UUID serverId = p.getServerId();
        UUID targetUserId = p.getTargetUserId();
        UUID requesterId = p.getRequesterId();

        if (!serverBanRepository.existsByServerIdAndUserId(serverId, targetUserId)) {
            serverBanRepository.save(ServerBan.builder()
                    .serverId(serverId)
                    .userId(targetUserId)
                    .bannedByUserId(requesterId)
                    .reason(p.getReason())
                    .build());
        }
        serverMemberRepository.findByServerIdAndUserId(serverId, targetUserId)
                .ifPresent(serverMemberRepository::delete);

        String forceMsg = gson.toJson(WsMessage.builder()
                .type(WsMessageType.FORCE_DISCONNECT)
                .payload(gson.toJsonTree(ForceDisconnectPayload.builder()
                        .reason("BANNED")
                        .build()).getAsJsonObject())
                .build());
        clientMessageSender.sendToUser(serverId, targetUserId, forceMsg);
        clientMessageSender.closeUserSession(serverId, targetUserId);

        String broadcastMsg = gson.toJson(WsMessage.builder()
                .type(WsMessageType.USER_BANNED)
                .payload(gson.toJsonTree(UserBannedPayload.builder()
                        .userId(targetUserId)
                        .bannedByUserId(requesterId)
                        .build()).getAsJsonObject())
                .build());
        clientMessageSender.broadcastToServerExcept(serverId, targetUserId, broadcastMsg);

        log.info("BAN_USER_NOTIFICATION: requesterId={} banned userId={} from serverId={}", requesterId, targetUserId, serverId);
    }
}
