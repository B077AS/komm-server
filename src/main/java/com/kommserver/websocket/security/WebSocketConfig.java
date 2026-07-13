package com.kommserver.websocket.security;

import com.kommserver.websocket.managers.ClientSessionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Registers a single {@code /ws} endpoint — the server-side mirror of the
 * client's {@code InstallationWsClient} which also connects to {@code /ws}.
 * <p>
 * Authentication is handled by {@link AuthHandshakeInterceptor} before the
 * connection is fully established, so no path variables or per-feature
 * endpoints are needed here.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final ClientSessionManager clientSessionManager;
    private final AuthHandshakeInterceptor authHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(clientSessionManager, "/ws")
                .addInterceptors(authHandshakeInterceptor)
                .setAllowedOrigins("*");
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(100 * 1024 * 1024); // 100 MB — base64 overhead makes a 50 MB file ~67 MB
        return container;
    }
}