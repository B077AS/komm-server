package com.kommserver.service;

import com.google.gson.Gson;
import com.kommserver.model.db.ServerMember;
import com.kommserver.repository.ServerMemberRepository;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.ForceDisconnectPayload;
import com.kommserver.websocket.messages.payloads.UserKickedPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KickService {

    private final ServerMemberRepository serverMemberRepository;
    private final ClientMessageSender clientMessageSender;
    private final Gson gson;

    @Transactional
    public void kickUser(UUID serverId, UUID requesterId, UUID targetUserId) {
        if (requesterId.equals(targetUserId)) return;

        ServerMember requesterMember = serverMemberRepository.findByServerIdAndUserId(serverId, requesterId).orElse(null);
        ServerMember targetMember = serverMemberRepository.findByServerIdAndUserId(serverId, targetUserId).orElse(null);
        if (requesterMember == null || targetMember == null) return;

        if (roleOrdinal(requesterMember.getRole()) <= roleOrdinal(targetMember.getRole())) {
            log.warn("KICK: userId={} (role={}) cannot kick userId={} (role={})",
                    requesterId, requesterMember.getRole(), targetUserId, targetMember.getRole());
            throw new IllegalStateException("Insufficient role to kick this user");
        }

        serverMemberRepository.delete(targetMember);

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

        log.info("KICK: requesterId={} kicked userId={} from serverId={}", requesterId, targetUserId, serverId);
    }

    private int roleOrdinal(ServerMember.Role role) {
        return switch (role) {
            case OWNER -> 3;
            case ADMIN -> 2;
            case MODERATOR -> 1;
            case MEMBER -> 0;
        };
    }
}
