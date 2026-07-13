package com.kommserver.websocket.interfaces;

import com.google.gson.JsonObject;
import com.kommserver.websocket.messages.WsMessageType;
import org.springframework.web.socket.WebSocketSession;

public interface ClientInboundMessageHandler {

    WsMessageType getType();

    void handle(WebSocketSession session, JsonObject payload);
}