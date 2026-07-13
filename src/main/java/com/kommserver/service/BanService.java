package com.kommserver.service;

import com.google.gson.Gson;
import com.kommserver.model.db.ServerBan;
import com.kommserver.model.db.ServerMember;
import com.kommserver.model.dto.request.BanRequest;
import com.kommserver.model.dto.summary.BannedUserSummary;
import com.kommserver.repository.ServerBanRepository;
import com.kommserver.repository.ServerMemberRepository;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.ForceDisconnectPayload;
import com.kommserver.websocket.messages.payloads.UserBannedPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BanService {

    private final ServerBanRepository serverBanRepository;
    private final ServerMemberRepository serverMemberRepository;
    private final ClientMessageSender clientMessageSender;
    private final Gson gson;

    public List<BannedUserSummary> getBannedUsers(UUID serverId) {
        return serverBanRepository.findAllByServerId(serverId).stream()
                .map(ban -> BannedUserSummary.builder()
                        .userId(ban.getUserId())
                        .reason(ban.getReason())
                        .bannedAt(ban.getBannedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional
    public void banUser(UUID serverId, UUID requesterId, BanRequest request) {
        UUID targetUserId = request.getUserId();
        if (requesterId.equals(targetUserId)) return;

        ServerMember requesterMember = serverMemberRepository.findByServerIdAndUserId(serverId, requesterId).orElse(null);
        ServerMember targetMember = serverMemberRepository.findByServerIdAndUserId(serverId, targetUserId).orElse(null);
        if (requesterMember == null || targetMember == null) return;

        if (roleOrdinal(requesterMember.getRole()) <= roleOrdinal(targetMember.getRole())) {
            log.warn("BAN: userId={} (role={}) cannot ban userId={} (role={})",
                    requesterId, requesterMember.getRole(), targetUserId, targetMember.getRole());
            throw new IllegalStateException("Insufficient role to ban this user");
        }

        if (!serverBanRepository.existsByServerIdAndUserId(serverId, targetUserId)) {
            serverBanRepository.save(ServerBan.builder()
                    .serverId(serverId)
                    .userId(targetUserId)
                    .bannedByUserId(requesterId)
                    .reason(request.getReason())
                    .build());
        }
        serverMemberRepository.delete(targetMember);

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

        log.info("BAN: requesterId={} banned userId={} from serverId={}", requesterId, targetUserId, serverId);
    }

    @Transactional
    public void unbanUser(UUID serverId, UUID targetUserId) {
        serverBanRepository.deleteByServerIdAndUserId(serverId, targetUserId);
        log.info("UNBAN: userId={} unbanned from serverId={}", targetUserId, serverId);
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
