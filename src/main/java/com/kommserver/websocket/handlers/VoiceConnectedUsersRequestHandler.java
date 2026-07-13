package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.Channel;
import com.kommserver.repository.ChannelRepository;
import com.kommserver.repository.ServerRepository;
import com.kommserver.websocket.senders.HubMessageSender;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.VoiceConnectedUsersRequestPayload;
import com.kommserver.websocket.messages.payloads.VoiceConnectedUsersResponsePayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceConnectedUsersRequestHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final HubMessageSender hubMessageSender;
    private final ChannelRepository channelRepository;
    private final ServerRepository serverRepository;

    @Override
    public WsMessageType getType() {
        return WsMessageType.VOCIE_CONNECTED_USERS_REQUEST;
    }

    @Override
    public void handle(JsonObject payload) {
        VoiceConnectedUsersRequestPayload request =
                gson.fromJson(payload, VoiceConnectedUsersRequestPayload.class);

        UUID serverId = request.getServerId();
        int count      = webrtcRoomsManager.getSessionsInServer(serverId).size();
        int textCount  = (int) channelRepository.countByServerIdAndChannelType(serverId, Channel.ChannelType.TEXT.name());
        int voiceCount = (int) channelRepository.countByServerIdAndChannelType(serverId, Channel.ChannelType.VOICE.name());

        Integer defaultPanelWidth = serverRepository.findById(serverId)
                .map(s -> s.getDefaultChannelPanelWidth())
                .orElse(null);

        VoiceConnectedUsersResponsePayload response = new VoiceConnectedUsersResponsePayload();
        response.setServerId(serverId);
        response.setCorrelationId(request.getCorrelationId());
        response.setActiveUsers(count);
        response.setTextChannelCount(textCount);
        response.setVoiceChannelCount(voiceCount);
        response.setDefaultChannelPanelWidth(defaultPanelWidth);

        hubMessageSender.send(WsMessageType.VOCIE_CONNECTED_USERS_RESPONSE, response);

        log.debug("ACTIVE_USERS_REQUEST: serverId={}, activeUsers={}, text={}, voice={}", serverId, count, textCount, voiceCount);
    }
}