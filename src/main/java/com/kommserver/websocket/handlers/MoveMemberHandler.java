package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.Channel;
import com.kommserver.model.db.Permission;
import com.kommserver.model.dto.summary.ChannelSummary;
import com.kommserver.repository.ChannelRepository;
import com.kommserver.repository.ServerMemberRepository;
import com.kommserver.service.PermissionService;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.managers.ClientSessionManager;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.ChannelCreatedPayload;
import com.kommserver.websocket.messages.payloads.ForcedChannelJoinPayload;
import com.kommserver.websocket.messages.payloads.MoveMemberPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MoveMemberHandler implements ClientInboundMessageHandler {

    private final Gson gson;
    private final ChannelRepository channelRepository;
    private final ServerMemberRepository serverMemberRepository;
    private final PermissionService permissionService;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final ClientMessageSender clientMessageSender;

    @Override
    public WsMessageType getType() {
        return WsMessageType.MOVE_MEMBER;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        MoveMemberPayload movePayload = gson.fromJson(payload, MoveMemberPayload.class);
        UUID moverId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);
        UUID targetUserId = movePayload.getTargetUserId();
        UUID destinationChannelId = movePayload.getDestinationChannelId();

        if (moverId == null || serverId == null || targetUserId == null || destinationChannelId == null) {
            log.warn("MOVE_MEMBER: missing required fields from session={}", session.getId());
            return;
        }

        if (moverId.equals(targetUserId)) {
            log.debug("MOVE_MEMBER: ignored self-move from userId={}", moverId);
            return;
        }

        Channel destination = channelRepository.findById(destinationChannelId).orElse(null);
        if (destination == null || !destination.getServerId().equals(serverId)) {
            log.warn("MOVE_MEMBER: destinationChannelId={} not found or not in serverId={}", destinationChannelId, serverId);
            return;
        }

        if (destination.getChannelType() != Channel.ChannelType.VOICE) {
            log.warn("MOVE_MEMBER: destination channelId={} is not a voice channel", destinationChannelId);
            return;
        }

        if (!serverMemberRepository.existsByServerIdAndUserId(serverId, targetUserId)) {
            log.warn("MOVE_MEMBER: targetUserId={} is not a member of serverId={}", targetUserId, serverId);
            return;
        }

        if (!permissionService.has(moverId, serverId, Permission.MOVE_MEMBERS)) {
            log.warn("MOVE_MEMBER: moverId={} missing server-level MOVE_MEMBERS (serverId={})", moverId, serverId);
            return;
        }

        WebrtcRoomsManager.ChannelRef currentRoom = webrtcRoomsManager.getCurrentRoomInServer(serverId, targetUserId);
        if (currentRoom == null) {
            log.debug("MOVE_MEMBER: targetUserId={} is not in a voice channel on serverId={}", targetUserId, serverId);
            return;
        }

        if (currentRoom.channelId().equals(destinationChannelId)) {
            log.debug("MOVE_MEMBER: targetUserId={} is already in destinationChannelId={}", targetUserId, destinationChannelId);
            return;
        }

        // If the target user can't see the destination channel, reveal it first so
        // the client has a card to join into. It will be hidden again on voice leave.
        if (!permissionService.hasInChannel(targetUserId, serverId, destinationChannelId, Permission.VIEW_CHANNEL)) {
            WsMessage reveal = WsMessage.builder()
                    .type(WsMessageType.CHANNEL_CREATED)
                    .payload(ChannelCreatedPayload.builder()
                            .channelId(destination.getChannelId())
                            .serverId(destination.getServerId())
                            .channelName(destination.getChannelName())
                            .channelType(ChannelSummary.ChannelType.valueOf(destination.getChannelType().name()))
                            .description(destination.getDescription())
                            .position(destination.getPosition() != null ? destination.getPosition() : 0)
                            .icon(destination.getIcon())
                            .build())
                    .build();
            clientMessageSender.sendToUser(serverId, targetUserId, gson.toJson(reveal));
        }

        webrtcRoomsManager.setPendingForcedJoin(targetUserId, destinationChannelId);

        WsMessage forced = WsMessage.builder()
                .type(WsMessageType.FORCED_CHANNEL_JOIN)
                .payload(gson.toJsonTree(ForcedChannelJoinPayload.builder()
                        .channelId(destinationChannelId)
                        .movedByUserId(moverId)
                        .build()).getAsJsonObject())
                .build();

        clientMessageSender.sendToUser(serverId, targetUserId, gson.toJson(forced));
        log.info("MOVE_MEMBER: moverId={} moved targetUserId={} to channelId={}", moverId, targetUserId, destinationChannelId);
    }
}
