package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.Permission;
import com.kommserver.repository.ServerMemberRepository;
import com.kommserver.service.PermissionService;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.managers.ClientSessionManager;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.ServerDeafenUserPayload;
import com.kommserver.websocket.messages.payloads.UserServerDeafenedPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ServerDeafenUserHandler implements ClientInboundMessageHandler {

    private final Gson gson;
    private final ClientMessageSender clientMessageSender;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final PermissionService permissionService;
    private final ServerMemberRepository serverMemberRepository;

    @Override
    public WsMessageType getType() {
        return WsMessageType.SERVER_DEAFEN_USER;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        UUID requesterId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);

        if (!permissionService.has(requesterId, serverId, Permission.DEAFEN_USERS)) {
            log.warn("SERVER_DEAFEN_USER denied: userId={} missing DEAFEN_USERS on serverId={}", requesterId, serverId);
            return;
        }

        ServerDeafenUserPayload req = gson.fromJson(payload, ServerDeafenUserPayload.class);
        UUID targetId = req.getTargetUserId();

        serverMemberRepository.findByServerIdAndUserId(serverId, targetId).ifPresent(member -> {
            member.setServerSpeakerEnabled(req.isServerSpeakerEnabled());
            serverMemberRepository.save(member);
        });

        WebrtcRoomsManager.ChannelRef room = webrtcRoomsManager.getCurrentRoom(targetId);
        if (room != null) {
            webrtcRoomsManager.updateServerSpeaker(room.serverId(), room.channelId(), targetId, req.isServerSpeakerEnabled());
        }

        WsMessage broadcast = WsMessage.builder()
                .type(WsMessageType.USER_SERVER_DEAFENED)
                .payload(UserServerDeafenedPayload.builder()
                        .userId(targetId)
                        .serverSpeakerEnabled(req.isServerSpeakerEnabled())
                        .build())
                .build();
        clientMessageSender.broadcastToServer(serverId, gson.toJson(broadcast));

        log.info("SERVER_DEAFEN_USER: requesterId={} set serverSpeakerEnabled={} for targetId={} on serverId={}",
                requesterId, req.isServerSpeakerEnabled(), targetId, serverId);
    }
}
