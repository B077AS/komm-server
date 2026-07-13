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
import com.kommserver.websocket.messages.payloads.ServerMuteUserPayload;
import com.kommserver.websocket.messages.payloads.UserServerMutedPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ServerMuteUserHandler implements ClientInboundMessageHandler {

    private final Gson gson;
    private final ClientMessageSender clientMessageSender;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final PermissionService permissionService;
    private final ServerMemberRepository serverMemberRepository;

    @Override
    public WsMessageType getType() {
        return WsMessageType.SERVER_MUTE_USER;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        UUID requesterId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);

        if (!permissionService.has(requesterId, serverId, Permission.MUTE_USERS)) {
            log.warn("SERVER_MUTE_USER denied: userId={} missing MUTE_USERS on serverId={}", requesterId, serverId);
            return;
        }

        ServerMuteUserPayload req = gson.fromJson(payload, ServerMuteUserPayload.class);
        UUID targetId = req.getTargetUserId();

        serverMemberRepository.findByServerIdAndUserId(serverId, targetId).ifPresent(member -> {
            member.setServerMicEnabled(req.isServerMicEnabled());
            serverMemberRepository.save(member);
        });

        WebrtcRoomsManager.ChannelRef room = webrtcRoomsManager.getCurrentRoom(targetId);
        if (room != null) {
            webrtcRoomsManager.updateServerMic(room.serverId(), room.channelId(), targetId, req.isServerMicEnabled());
        }

        WsMessage broadcast = WsMessage.builder()
                .type(WsMessageType.USER_SERVER_MUTED)
                .payload(UserServerMutedPayload.builder()
                        .userId(targetId)
                        .serverMicEnabled(req.isServerMicEnabled())
                        .build())
                .build();
        clientMessageSender.broadcastToServer(serverId, gson.toJson(broadcast));

        log.info("SERVER_MUTE_USER: requesterId={} set serverMicEnabled={} for targetId={} on serverId={}",
                requesterId, req.isServerMicEnabled(), targetId, serverId);
    }
}
