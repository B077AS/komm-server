package com.kommserver.websocket.managers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kommserver.repository.ServerBanRepository;
import com.kommserver.repository.ServerRepository;
import com.kommserver.sfu.LiveKitTokenService;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.UserSessionEntry;
import com.kommserver.websocket.messages.payloads.ForceDisconnectPayload;
import com.kommserver.websocket.messages.payloads.StreamEndedPayload;
import com.kommserver.websocket.messages.payloads.StreamViewerCountPayload;
import com.kommserver.websocket.messages.payloads.UserLeftChannelPayload;
import com.kommserver.websocket.security.AuthHandshakeInterceptor;
import com.kommserver.websocket.senders.ClientMessageSender;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClientSessionManager extends TextWebSocketHandler {

    private final List<ClientInboundMessageHandler> handlerList;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final LiveKitTokenService liveKitTokenService;
    private final ClientMessageSender clientMessageSender;
    private final ServerBanRepository serverBanRepository;
    private final ServerRepository serverRepository;
    private final Gson gson;

    private Map<WsMessageType, ClientInboundMessageHandler> handlers;

    @PostConstruct
    private void init() {
        handlers = handlerList.stream()
                .collect(Collectors.toMap(ClientInboundMessageHandler::getType, Function.identity()));
        log.info("ClientSessionManager initialised with handlers: {}", handlers.keySet());
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID userId = userId(session);
        UUID serverId = serverId(session);
        if (userId != null && serverId != null) {
            if (serverBanRepository.existsByServerIdAndUserId(serverId, userId)) {
                log.warn("Banned userId={} rejected for serverId={}", userId, serverId);
                try { session.close(CloseStatus.POLICY_VIOLATION); } catch (Exception ignored) {}
                return;
            }
            boolean pendingDeletion = serverRepository.findById(serverId)
                    .map(s -> Boolean.TRUE.equals(s.getPendingDeletion()))
                    .orElse(true); // server row gone → nothing to join
            if (pendingDeletion) {
                log.warn("Join rejected — serverId={} is pending deletion", serverId);
                try { session.close(CloseStatus.POLICY_VIOLATION); } catch (Exception ignored) {}
                return;
            }
            // Evict any existing session for this user on this server (duplicate login).
            clientMessageSender.sendToUser(serverId, userId, gson.toJson(
                    WsMessage.builder()
                            .type(WsMessageType.FORCE_DISCONNECT)
                            .payload(ForceDisconnectPayload.builder()
                                    .reason("DUPLICATE_SESSION")
                                    .build())
                            .build()));
            clientMessageSender.closeUserSession(serverId, userId);

            clientMessageSender.register(serverId, userId, session);
        }
        log.info("Client connected — sessionId={} userId={} serverId={}", session.getId(), userId, serverId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID userId = userId(session);
        UUID serverId = serverId(session);
        if (userId != null && serverId != null) {
            clientMessageSender.unregister(serverId, userId, session);
            webrtcRoomsManager.clearPendingForcedJoin(userId);

            // Capture the voice channel (if any) BEFORE eviction so we can tell the
            // rest of the server the user left — this drives connected-user-card
            // removal and stops any soundboard sounds they had triggered, exactly as
            // a clean CHANNEL_LEAVE would.
            WebrtcRoomsManager.ChannelRef room = webrtcRoomsManager.getCurrentRoomInServer(serverId, userId);
            UserSessionEntry entry = (room != null)
                    ? webrtcRoomsManager.getEntry(serverId, room.channelId(), userId) : null;
            boolean wasStreaming = entry != null && entry.isScreenSharingEnabled();

            webrtcRoomsManager.evict(userId).forEach(ref ->
                    liveKitTokenService.deleteRoom(ref.serverId(), ref.channelId()));

            if (room != null) {
                WsMessage broadcast = WsMessage.builder()
                        .type(WsMessageType.USER_LEFT_CHANNEL)
                        .payload(UserLeftChannelPayload.builder()
                                .channelId(room.channelId())
                                .userId(userId)
                                .build())
                        .build();
                clientMessageSender.broadcastToServer(serverId, gson.toJson(broadcast));
            }

            webrtcRoomsManager.removeAsWatcher(userId).forEach((streamerId, count) ->
                    clientMessageSender.broadcastToServer(serverId, gson.toJson(
                            WsMessage.builder()
                                    .type(WsMessageType.STREAM_VIEWER_COUNT)
                                    .payload(StreamViewerCountPayload.builder()
                                            .streamerUserId(streamerId)
                                            .count(count)
                                            .build())
                                    .build())));

            if (wasStreaming) {
                webrtcRoomsManager.clearWatchersForStreamer(userId);
                clientMessageSender.broadcastToServer(serverId, gson.toJson(
                        WsMessage.builder()
                                .type(WsMessageType.STREAM_VIEWER_COUNT)
                                .payload(StreamViewerCountPayload.builder()
                                        .streamerUserId(userId)
                                        .count(0)
                                        .build())
                                .build()));
                clientMessageSender.broadcastToServer(serverId, gson.toJson(
                        WsMessage.builder()
                                .type(WsMessageType.STREAM_ENDED)
                                .payload(StreamEndedPayload.builder()
                                        .streamerUserId(userId)
                                        .build())
                                .build()));
            }
        }
        log.info("Client disconnected — sessionId={} status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable e) {
        log.warn("Transport error — sessionId={}: {}", session.getId(), e.getMessage());
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (!session.isOpen()) {
            log.debug("Discarding message from closed session={}", session.getId());
            return;
        }

        try {
            JsonObject root = JsonParser.parseString(message.getPayload()).getAsJsonObject();
            WsMessageType type = WsMessageType.valueOf(root.get("type").getAsString());
            JsonObject payload = root.has("payload") && !root.get("payload").isJsonNull()
                    ? root.getAsJsonObject("payload")
                    : new JsonObject();

            ClientInboundMessageHandler handler = handlers.get(type);
            if (handler != null) {
                handler.handle(session, payload);
            } else {
                log.warn("No handler registered for message type: {}", type);
            }

        } catch (IllegalArgumentException e) {
            log.warn("Unknown message type: {}", message.getPayload());
        } catch (Exception e) {
            log.error("Error handling client message", e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public static UUID userId(WebSocketSession session) {
        return (UUID) session.getAttributes().get(AuthHandshakeInterceptor.ATTR_USER_ID);
    }

    public static UUID serverId(WebSocketSession session) {
        return (UUID) session.getAttributes().get(AuthHandshakeInterceptor.ATTR_SERVER_ID);
    }
}