package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.dto.summary.BannedUserSummary;
import com.kommserver.repository.ServerBanRepository;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.GetBansRequestPayload;
import com.kommserver.websocket.messages.payloads.PermProxyResponsePayload;
import com.kommserver.websocket.senders.HubMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class GetBansRequestHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final ServerBanRepository serverBanRepository;
    private final HubMessageSender hubMessageSender;

    @Override
    public WsMessageType getType() { return WsMessageType.GET_BANS_REQUEST; }

    @Override
    public void handle(JsonObject payload) {
        GetBansRequestPayload req = gson.fromJson(payload, GetBansRequestPayload.class);
        try {
            List<BannedUserSummary> bans = serverBanRepository.findAllByServerId(req.getServerId()).stream()
                    .map(ban -> BannedUserSummary.builder()
                            .userId(ban.getUserId())
                            .reason(ban.getReason())
                            .bannedAt(ban.getBannedAt())
                            .build())
                    .collect(Collectors.toList());
            hubMessageSender.send(WsMessageType.GET_BANS_RESPONSE, PermProxyResponsePayload.builder()
                    .correlationId(req.getCorrelationId())
                    .success(true)
                    .data(gson.toJsonTree(bans))
                    .build());
        } catch (Exception e) {
            log.error("GET_BANS_REQUEST failed: {}", e.getMessage());
            hubMessageSender.send(WsMessageType.GET_BANS_RESPONSE, PermProxyResponsePayload.builder()
                    .correlationId(req.getCorrelationId())
                    .success(false)
                    .error(e.getMessage())
                    .build());
        }
    }
}
