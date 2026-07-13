package com.kommserver.websocket.managers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kommserver.websocket.ReconnectScheduler;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.senders.HubMessageSender;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class HubSessionManager extends TextWebSocketHandler {

    private final List<HubInboundMessageHandler> handlerList;
    private final HubMessageSender hubMessageSender;
    private final ReconnectScheduler reconnectScheduler;

    private Map<WsMessageType, HubInboundMessageHandler> handlers;

    @PostConstruct
    private void init() {
        handlers = handlerList.stream()
                .collect(Collectors.toMap(HubInboundMessageHandler::getType, Function.identity()));
        log.info("HubSessionManager initialised with handlers: {}", handlers.keySet());
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        hubMessageSender.setSession(session);
        reconnectScheduler.resetFlag();
        log.info("Hub WS session open: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        hubMessageSender.clearSession();
        log.warn("Hub WS closed: {}", status);
        reconnectScheduler.scheduleReconnect();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable e) {
        log.error("Hub WS transport error: {}", e.getMessage());
        reconnectScheduler.scheduleReconnect();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonObject root = JsonParser.parseString(message.getPayload()).getAsJsonObject();
            WsMessageType type = WsMessageType.valueOf(root.get("type").getAsString());
            JsonObject payload = root.has("payload") && !root.get("payload").isJsonNull()
                    ? root.getAsJsonObject("payload")
                    : new JsonObject();

            HubInboundMessageHandler handler = handlers.get(type);
            if (handler != null) {
                handler.handle(payload);
            } else {
                log.warn("Unhandled message type from hub: {}", type);
            }

        } catch (IllegalArgumentException e) {
            log.warn("Unknown message type from hub: {}", message.getPayload());
        } catch (Exception e) {
            log.error("Error handling hub message", e);
        }
    }
}