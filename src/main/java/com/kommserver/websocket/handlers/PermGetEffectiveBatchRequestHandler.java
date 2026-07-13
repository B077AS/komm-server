package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.service.PermissionService;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.PermGetEffectiveBatchRequestPayload;
import com.kommserver.websocket.messages.payloads.PermProxyResponsePayload;
import com.kommserver.websocket.senders.HubMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class PermGetEffectiveBatchRequestHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final PermissionService permissionService;
    private final HubMessageSender hubMessageSender;

    @Override
    public WsMessageType getType() { return WsMessageType.PERM_GET_EFFECTIVE_BATCH_REQUEST; }

    @Override
    public void handle(JsonObject payload) {
        PermGetEffectiveBatchRequestPayload req = gson.fromJson(payload, PermGetEffectiveBatchRequestPayload.class);
        try {
            Map<String, List<String>> result = req.getServerIds().stream()
                    .collect(Collectors.toMap(
                            UUID::toString,
                            serverId -> List.copyOf(permissionService.effectiveServerPermissions(req.getUserId(), serverId))
                    ));
            hubMessageSender.send(WsMessageType.PERM_GET_EFFECTIVE_BATCH_RESPONSE, PermProxyResponsePayload.builder()
                    .correlationId(req.getCorrelationId())
                    .success(true)
                    .data(gson.toJsonTree(result))
                    .build());
        } catch (Exception e) {
            log.error("PERM_GET_EFFECTIVE_BATCH_REQUEST failed: {}", e.getMessage());
            hubMessageSender.send(WsMessageType.PERM_GET_EFFECTIVE_BATCH_RESPONSE, PermProxyResponsePayload.builder()
                    .correlationId(req.getCorrelationId())
                    .success(false)
                    .error(e.getMessage())
                    .build());
        }
    }
}
