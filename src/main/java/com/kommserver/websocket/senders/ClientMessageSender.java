package com.kommserver.websocket.senders;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ClientMessageSender {

    // serverId → { userId → WebSocketSession }
    private final Map<UUID, Map<UUID, WebSocketSession>> serverSessions = new ConcurrentHashMap<>();

    public void register(UUID serverId, UUID userId, WebSocketSession session) {
        serverSessions
                .computeIfAbsent(serverId, k -> new ConcurrentHashMap<>())
                .put(userId, session);
    }

    public void unregister(UUID serverId, UUID userId, WebSocketSession session) {
        Map<UUID, WebSocketSession> bucket = serverSessions.get(serverId);
        if (bucket != null) {
            // Only remove if the registered session is still the one being closed.
            // If a duplicate-session eviction already replaced it, keep the new one.
            bucket.remove(userId, session);
            if (bucket.isEmpty()) {
                serverSessions.remove(serverId);
            }
        }
    }

    public void broadcastToServer(UUID serverId, String message) {
        Map<UUID, WebSocketSession> bucket = serverSessions.getOrDefault(serverId, Map.of());
        bucket.values().forEach(s -> {
            if (s.isOpen()) {
                try {
                    s.sendMessage(new TextMessage(message));
                } catch (Exception e) {
                    log.error("Failed to broadcast to session={}", s.getId(), e);
                }
            }
        });
    }

    public void broadcastToServerExcept(UUID serverId, UUID excludedUserId, String message) {
        Map<UUID, WebSocketSession> bucket = serverSessions.getOrDefault(serverId, Map.of());
        bucket.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(excludedUserId))
                .map(Map.Entry::getValue)
                .forEach(s -> {
                    if (s.isOpen()) {
                        try {
                            s.sendMessage(new TextMessage(message));
                        } catch (Exception e) {
                            log.error("Failed to broadcast to session={}", s.getId(), e);
                        }
                    }
                });
    }

    public void sendToUser(UUID serverId, UUID userId, String message) {
        Map<UUID, WebSocketSession> bucket = serverSessions.get(serverId);
        if (bucket == null) return;
        WebSocketSession session = bucket.get(userId);
        if (session != null && session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(message));
            } catch (Exception e) {
                log.error("Failed to send to userId={}", userId, e);
            }
        }
    }

    public void closeUserSession(UUID serverId, UUID userId) {
        Map<UUID, WebSocketSession> bucket = serverSessions.get(serverId);
        if (bucket == null) return;
        WebSocketSession session = bucket.get(userId);
        if (session != null && session.isOpen()) {
            try {
                session.close(CloseStatus.NORMAL);
            } catch (Exception e) {
                log.error("Failed to close session for userId={}", userId, e);
            }
        }
    }

    public Set<UUID> getOnlineUserIds(UUID serverId) {
        Map<UUID, WebSocketSession> bucket = serverSessions.get(serverId);
        if (bucket == null) return Collections.emptySet();
        return new HashSet<>(bucket.keySet());
    }

    public int countSessions(UUID serverId) {
        Map<UUID, WebSocketSession> bucket = serverSessions.get(serverId);
        return bucket == null ? 0 : bucket.size();
    }
}