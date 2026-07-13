package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.Channel;
import com.kommserver.model.db.Permission;
import com.kommserver.repository.ChannelRepository;
import com.kommserver.repository.ServerMemberRepository;
import com.kommserver.service.PermissionService;
import com.kommserver.websocket.senders.ClientMessageSender;
import com.kommserver.websocket.managers.ClientSessionManager;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.ChannelTypingPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

// ChannelTypingHandler.java (server)
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelTypingHandler implements ClientInboundMessageHandler {

    private final Gson gson;
    private final ChannelRepository channelRepository;
    private final ServerMemberRepository serverMemberRepository;
    private final ClientMessageSender clientMessageSender;
    private final PermissionService permissionService;

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNEL_TYPING_INDICATOR;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        UUID userId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);

        ChannelTypingPayload typing = gson.fromJson(payload, ChannelTypingPayload.class);

        Channel channel = channelRepository.findById(typing.getChannelId()).orElse(null);
        if (channel == null || !channel.getServerId().equals(serverId)) return;
        if (!serverMemberRepository.existsByServerIdAndUserId(serverId, userId)) return;
        if (!permissionService.hasInChannel(userId, serverId, typing.getChannelId(), Permission.SEND_MESSAGES)) return;

        ChannelTypingPayload outbound = ChannelTypingPayload.builder()
                .channelId(typing.getChannelId())
                .serverId(serverId)
                .userId(userId)
                .build();

        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.CHANNEL_TYPING_INDICATOR)
                .payload(outbound)
                .build();

        clientMessageSender.broadcastToServer(serverId, gson.toJson(msg));
    }
}