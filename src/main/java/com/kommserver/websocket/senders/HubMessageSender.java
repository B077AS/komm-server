package com.kommserver.websocket.senders;

import com.google.gson.Gson;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
@RequiredArgsConstructor
public class HubMessageSender {

    private final Gson gson;
    private volatile WebSocketSession hubSession;

    public void setSession(WebSocketSession session) {
        this.hubSession = session;
    }

    public void clearSession() {
        this.hubSession = null;
    }

    public void send(WsMessageType type, Object payload) {
        WebSocketSession s = hubSession;
        if (s == null || !s.isOpen()) {
            log.warn("Cannot send {} to hub — session not open", type);
            return;
        }
        try {
            WsMessage message = WsMessage.builder().type(type).payload(payload).build();
            s.sendMessage(new TextMessage(gson.toJson(message)));
        } catch (Exception e) {
            log.error("Failed to send {} to hub", type, e);
        }
    }
}