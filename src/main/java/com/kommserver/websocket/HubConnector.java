package com.kommserver.websocket;

import com.kommserver.model.db.Installation;
import com.kommserver.repository.InstallationRepository;
import com.kommserver.security.JwtUtil;
import com.kommserver.security.TlsMaterialService;
import com.kommserver.websocket.managers.HubSessionManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.util.Base64;

@Slf4j
@Component
@RequiredArgsConstructor
public class HubConnector {

    private final InstallationRepository installationRepository;
    private final HubSessionManager hubSessionManager;
    private final ReconnectScheduler reconnectScheduler;
    private final JwtUtil jwtUtil;
    private final TlsMaterialService tlsMaterialService;

    @Value("${websocket.url}")
    private String hubWsUrl;

    private WebSocketSession session;

    @PostConstruct
    public void init() {
        reconnectScheduler.setConnectAction(this::connect);
    }

    public void connect() {
        Installation installation = installationRepository.findAll()
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No installation record found"));

        String encodedCert = Base64.getEncoder()
                .encodeToString(installation.getCertificate().getBytes());
        String connectToken = jwtUtil.generateConnectToken(installation.getInstallationId());

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Authorization", "Certificate " + encodedCert);
        headers.add("X-Connect-Token", connectToken);
        // Tells the hub whether app clients should reach us via wss:// or ws://
        headers.add("X-Tls-Enabled", String.valueOf(tlsMaterialService.isServingTls()));

        StandardWebSocketClient client = new StandardWebSocketClient();
        client.execute(hubSessionManager, headers, URI.create(hubWsUrl))
                .thenAccept(s -> {
                    this.session = s;
                    log.info("Connected to hub at {}", hubWsUrl);
                })
                .exceptionally(e -> {
                    log.error("Hub connection failed: {}", e.getMessage());
                    reconnectScheduler.scheduleReconnect();
                    return null;
                });
    }

    public boolean isConnected() {
        return session != null && session.isOpen();
    }
}