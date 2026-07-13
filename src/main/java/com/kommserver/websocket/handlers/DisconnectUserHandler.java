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
import com.kommserver.websocket.messages.payloads.DisconnectUserPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DisconnectUserHandler implements ClientInboundMessageHandler {

    private final Gson gson;
    private final PermissionService permissionService;
    private final ServerMemberRepository serverMemberRepository;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final ClientMessageSender clientMessageSender;

    @Override
    public WsMessageType getType() {
        return WsMessageType.DISCONNECT_USER;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        UUID requesterId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);
        DisconnectUserPayload req = gson.fromJson(payload, DisconnectUserPayload.class);
        UUID targetUserId = req.getTargetUserId();

        if (requesterId == null || serverId == null || targetUserId == null) return;

        if (requesterId.equals(targetUserId)) {
            log.warn("DISCONNECT_USER: userId={} attempted self-disconnect", requesterId);
            return;
        }

        if (!permissionService.has(requesterId, serverId, Permission.MOVE_MEMBERS)) {
            log.warn("DISCONNECT_USER: userId={} missing MOVE_MEMBERS on serverId={}", requesterId, serverId);
            return;
        }

        if (!serverMemberRepository.existsByServerIdAndUserId(serverId, targetUserId)) {
            log.warn("DISCONNECT_USER: targetUserId={} is not a member of serverId={}", targetUserId, serverId);
            return;
        }

        WebrtcRoomsManager.ChannelRef currentRoom = webrtcRoomsManager.getCurrentRoomInServer(serverId, targetUserId);
        if (currentRoom == null) {
            log.debug("DISCONNECT_USER: targetUserId={} is not in a voice channel on serverId={}", targetUserId, serverId);
            return;
        }

        String msg = gson.toJson(WsMessage.builder()
                .type(WsMessageType.FORCED_VOICE_DISCONNECT)
                .payload(new JsonObject())
                .build());
        clientMessageSender.sendToUser(serverId, targetUserId, msg);

        log.info("DISCONNECT_USER: userId={} disconnected targetUserId={} from voice on serverId={}", requesterId, targetUserId, serverId);
    }
}
