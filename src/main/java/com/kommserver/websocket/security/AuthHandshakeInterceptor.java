package com.kommserver.websocket.security;

import com.kommserver.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Validates the Bearer token from the {@code Authorization} header before the
 * WebSocket handshake completes.  On success, the resolved {@code userId} is
 * stored in the session attributes so every downstream handler can read it
 * without touching JWT again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_SERVER_ID = "serverId";

    private final JwtUtil jwtUtil;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        List<String> authHeaders = request.getHeaders().get("Authorization");
        if (authHeaders == null || authHeaders.isEmpty()) {
            log.warn("WS handshake rejected — missing Authorization header");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String header = authHeaders.get(0);
        if (!header.startsWith("Bearer ")) {
            log.warn("WS handshake rejected — malformed Authorization header");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String token = header.substring(7);
        try {
            UUID userId = jwtUtil.extractUserId(token);
            UUID serverId = jwtUtil.extractServerId(token);
            attributes.put(ATTR_USER_ID, userId);
            attributes.put(ATTR_SERVER_ID, serverId);
            return true;
        } catch (Exception e) {
            log.warn("WS handshake rejected — invalid token: {}", e.getMessage());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}