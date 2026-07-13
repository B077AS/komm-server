package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.managers.ClientSessionManager;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.StreamViewerCountPayload;
import com.kommserver.websocket.messages.payloads.StreamWatchPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class StreamWatchHandler implements ClientInboundMessageHandler {

    private final Gson gson;
    private final ClientMessageSender clientMessageSender;
    private final WebrtcRoomsManager webrtcRoomsManager;

    @Override
    public WsMessageType getType() {
        return WsMessageType.STREAM_WATCH;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        UUID watcherUserId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);

        StreamWatchPayload p = gson.fromJson(payload, StreamWatchPayload.class);
        UUID streamerUserId = p.getStreamerUserId();
        if (streamerUserId == null) return;

        int count = webrtcRoomsManager.addWatcher(streamerUserId, watcherUserId);

        clientMessageSender.broadcastToServer(serverId, gson.toJson(
                WsMessage.builder()
                        .type(WsMessageType.STREAM_VIEWER_COUNT)
                        .payload(StreamViewerCountPayload.builder()
                                .streamerUserId(streamerUserId)
                                .count(count)
                                .build())
                        .build()));
    }
}
