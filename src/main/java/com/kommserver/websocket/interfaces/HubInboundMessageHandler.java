package com.kommserver.websocket.interfaces;

import com.google.gson.JsonObject;
import com.kommserver.websocket.messages.WsMessageType;


public interface HubInboundMessageHandler {
    WsMessageType getType();
    void handle(JsonObject payload);
}