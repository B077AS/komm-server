package com.kommserver.sfu;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * LiveKit signaling, served on this server's own API port at {@value #PATH}.
 * <p>
 * LiveKit cannot terminate TLS on its signal port, so it listens on loopback only
 * ({@link SfuLauncher}) and every client signal connection goes through here instead:
 * over wss with the hub-issued certificate when TLS is on, plain ws in the
 * no-certificate fallback. The join token (query string) and the SDP — including the
 * DTLS fingerprints that the media encryption hangs on — never cross the network in
 * the clear. Frames are relayed verbatim in both directions; this class does not parse
 * the LiveKit protocol.
 * <p>
 * The upstream connection is opened during the client's HTTP handshake
 * ({@link #beforeHandshake}), so LiveKit's own rejection (401 for a bad or expired
 * token, …) reaches the client as the HTTP status of its upgrade request — the same
 * thing it saw when it talked to LiveKit directly.
 * <p>
 * Keepalive: LiveKit pings its socket every 10 s, but that socket now ends at this proxy
 * (on loopback), so those pings never reach the client. Without traffic, an idle signal
 * connection over the internet gets silently dropped by NAT routers/firewalls — neither
 * end notices, and the client stops receiving refresh tokens, speaker updates and
 * participant changes. The proxy therefore pings the client itself every
 * {@link #CLIENT_PING_INTERVAL} and, when nothing (pong or frame) has come back for
 * {@link #CLIENT_DEAD_AFTER}, closes both sides so the client and LiveKit both see it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveKitSignalProxy extends AbstractWebSocketHandler implements HandshakeInterceptor {

    /** Path on the API port; also what {@link LiveKitTokenService#getLiveKitUrl()} advertises. */
    public static final String PATH = "/rtc";

    private static final String UPSTREAM_ATTR = LiveKitSignalProxy.class.getName() + ".upstream";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** An upstream whose client handshake never completed is dropped after this. */
    private static final Duration ATTACH_TIMEOUT = Duration.ofSeconds(15);
    private static final int CLIENT_SEND_TIME_LIMIT_MS = 10_000;
    private static final int CLIENT_SEND_BUFFER_LIMIT = 4 * 1024 * 1024;
    /** Like LiveKit's own ping cadence — well under any NAT idle timeout. */
    private static final Duration CLIENT_PING_INTERVAL = Duration.ofSeconds(15);
    /** Three missed pings: the client (or the path to it) is gone. */
    private static final Duration CLIENT_DEAD_AFTER = Duration.ofSeconds(45);
    private static final CloseStatus KEEPALIVE_TIMEOUT = CloseStatus.GOING_AWAY.withReason("keepalive timeout");

    /** Only checks timestamps and hands sends off to virtual threads, so one thread serves all sessions. */
    private static final ScheduledExecutorService KEEPALIVE = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "livekit-proxy-keepalive");
        t.setDaemon(true);
        return t;
    });

    private final SfuLauncher sfuLauncher;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    // ── Handshake: open LiveKit first ─────────────────────────────────────────

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!sfuLauncher.isRunning()) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }

        URI target = upstreamUri(request.getURI());
        Upstream upstream = new Upstream();
        WebSocket.Builder builder = httpClient.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT);
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            // LiveKit only logs it, but that keeps its logs showing the real client.
            builder.header("X-Forwarded-For", remote.getAddress().getHostAddress());
        }

        try {
            upstream.ws = builder.buildAsync(target, upstream)
                    .get(CONNECT_TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof WebSocketHandshakeException hs) {
                int status = hs.getResponse().statusCode();
                log.info("[SFU] LiveKit refused signal connection from {}: HTTP {}", remote, status);
                response.setStatusCode(HttpStatusCode.valueOf(status));
            } else {
                log.warn("[SFU] Could not reach LiveKit for signaling: {}", e.getCause().toString());
                response.setStatusCode(HttpStatus.BAD_GATEWAY);
            }
            return false;
        } catch (TimeoutException e) {
            log.warn("[SFU] Timed out connecting to LiveKit for signaling");
            response.setStatusCode(HttpStatus.GATEWAY_TIMEOUT);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }

        attributes.put(UPSTREAM_ATTR, upstream);
        // afterHandshake gets no attributes, so a handshake that fails after this point
        // can't be cleaned up there — the upstream drops itself if never attached.
        CompletableFuture.delayedExecutor(ATTACH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .execute(upstream::abortIfUnattached);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    // ── Client side ───────────────────────────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Upstream upstream = upstream(session);
        if (upstream == null) {
            closeQuietly(session, CloseStatus.SERVER_ERROR);
            return;
        }
        // Upstream frames arrive on HttpClient threads while client frames are being
        // relayed; the decorator serializes sends to the client session.
        upstream.attach(new ConcurrentWebSocketSessionDecorator(
                session, CLIENT_SEND_TIME_LIMIT_MS, CLIENT_SEND_BUFFER_LIMIT));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Upstream upstream = upstream(session);
        if (upstream == null) return;
        upstream.markAlive();
        upstream.sendBinary(message.getPayload());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Upstream upstream = upstream(session);
        if (upstream == null) return;
        upstream.markAlive();
        upstream.sendText(message.getPayload());
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        Upstream upstream = upstream(session);
        if (upstream != null) upstream.markAlive();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("[SFU] Signal client transport error: {}", exception.toString());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Upstream upstream = upstream(session);
        if (upstream != null) upstream.closeFromClient(status);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private URI upstreamUri(URI requestUri) {
        String base = sfuLauncher.getInternalBaseUrl().replaceFirst("^http", "ws");
        String query = requestUri.getRawQuery();
        return URI.create(base + requestUri.getRawPath() + (query != null ? "?" + query : ""));
    }

    private static Upstream upstream(WebSocketSession session) {
        return (Upstream) session.getAttributes().get(UPSTREAM_ATTR);
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) session.close(status);
        } catch (Exception ignored) {
        }
    }

    /** A close code that may legally be sent in a close frame (RFC 6455 §7.4). */
    private static int sendableCloseCode(int code) {
        boolean reserved = code == 1004 || code == 1005 || code == 1006 || code == 1015;
        return (code < 1000 || code > 4999 || reserved) ? CloseStatus.SERVER_ERROR.getCode() : code;
    }

    /**
     * The loopback connection to LiveKit for one client session. Frames LiveKit sends
     * before the client session is attached (the join response follows the upgrade
     * immediately) are queued and flushed on attach.
     */
    private static final class Upstream implements WebSocket.Listener {

        private volatile WebSocket ws;

        private final Object lock = new Object();
        private WebSocketSession client;                 // guarded by lock
        private final Queue<Object> pending = new ArrayDeque<>(); // guarded by lock
        private boolean closed;                          // guarded by lock

        /** java.net.http.WebSocket allows one outstanding send at a time — chain them. */
        private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null); // guarded by lock

        private final ByteArrayOutputStream binaryParts = new ByteArrayOutputStream();
        private final StringBuilder textParts = new StringBuilder();

        /** Last time anything (pong or frame) arrived from the client. */
        private volatile long lastClientActivityNanos = System.nanoTime();
        private ScheduledFuture<?> keepalive;            // guarded by lock

        // client → LiveKit

        void attach(WebSocketSession session) {
            synchronized (lock) {
                if (closed) {
                    closeQuietly(session, CloseStatus.GOING_AWAY);
                    return;
                }
                client = session;
                Object frame;
                while ((frame = pending.poll()) != null) {
                    deliver(session, frame);
                }
                markAlive();
                long period = CLIENT_PING_INTERVAL.toMillis();
                keepalive = KEEPALIVE.scheduleAtFixedRate(this::keepaliveTick, period, period, TimeUnit.MILLISECONDS);
            }
        }

        void markAlive() {
            lastClientActivityNanos = System.nanoTime();
        }

        private void keepaliveTick() {
            WebSocketSession session;
            synchronized (lock) {
                if (closed || client == null) return;
                session = client;
            }
            long silentMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastClientActivityNanos);
            if (silentMs >= CLIENT_DEAD_AFTER.toMillis()) {
                log.info("[SFU] Signal client {} silent for {} s — closing its LiveKit connection",
                        session.getRemoteAddress(), silentMs / 1000);
                Thread.startVirtualThread(() -> failClient(KEEPALIVE_TIMEOUT)); // the close frame can block too
                return;
            }
            // A send can block (full socket buffer towards a dead peer); never on this shared thread.
            Thread.startVirtualThread(() -> {
                try {
                    session.sendMessage(new PingMessage());
                } catch (Exception e) {
                    log.debug("[SFU] Ping to signal client failed: {}", e.toString());
                }
            });
        }

        /** Must be called with {@link #lock} held. */
        private void stopKeepalive() {
            if (keepalive != null) {
                keepalive.cancel(false);
                keepalive = null;
            }
        }

        void sendBinary(ByteBuffer payload) {
            ByteBuffer copy = ByteBuffer.allocate(payload.remaining()).put(payload).flip();
            enqueueSend(w -> w.sendBinary(copy, true));
        }

        void sendText(String payload) {
            enqueueSend(w -> w.sendText(payload, true));
        }

        void closeFromClient(CloseStatus status) {
            WebSocket w = ws;
            synchronized (lock) {
                if (closed) return;
                closed = true;
                pending.clear();
                stopKeepalive();
                if (w == null) return;
                // Forward a real close frame (LiveKit treats it as the client going away),
                // then drop the socket whether or not LiveKit answers.
                sendChain = sendChain
                        .handle((r, e) -> null)
                        .thenCompose(v -> w.sendClose(sendableCloseCode(status.getCode()), ""))
                        .orTimeout(5, TimeUnit.SECONDS)
                        .whenComplete((r, e) -> w.abort());
            }
        }

        void abortIfUnattached() {
            WebSocket w = ws;
            synchronized (lock) {
                if (client != null || closed) return;
                closed = true;
                pending.clear();
            }
            log.debug("[SFU] Dropping LiveKit signal connection whose client never attached");
            if (w != null) w.abort();
        }

        private void enqueueSend(java.util.function.Function<WebSocket, CompletableFuture<WebSocket>> send) {
            WebSocket w = ws;
            synchronized (lock) {
                if (closed || w == null) return;
                sendChain = sendChain.thenCompose(v -> send.apply(w));
                sendChain.exceptionally(e -> {
                    log.debug("[SFU] Relaying to LiveKit failed: {}", e.toString());
                    failClient(CloseStatus.SERVER_ERROR);
                    return null;
                });
            }
        }

        // LiveKit → client

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binaryParts.writeBytes(chunk);
            if (last) {
                byte[] whole = binaryParts.toByteArray();
                binaryParts.reset();
                toClient(new BinaryMessage(whole));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textParts.append(data);
            if (last) {
                String whole = textParts.toString();
                textParts.setLength(0);
                toClient(new TextMessage(whole));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            failClient(new CloseStatus(sendableCloseCode(statusCode), reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.debug("[SFU] LiveKit signal connection error: {}", error.toString());
            failClient(CloseStatus.SERVER_ERROR);
        }

        private void toClient(Object frame) {
            synchronized (lock) {
                if (closed) return;
                if (client == null) {
                    pending.add(frame);
                } else {
                    deliver(client, frame);
                }
            }
        }

        private void failClient(CloseStatus status) {
            WebSocketSession session;
            WebSocket w = ws;
            synchronized (lock) {
                if (closed) return;
                closed = true;
                pending.clear();
                stopKeepalive();
                session = client;
            }
            if (session != null) closeQuietly(session, status);
            if (w != null) w.abort();
        }

        private static void deliver(WebSocketSession session, Object frame) {
            try {
                if (frame instanceof BinaryMessage b) {
                    session.sendMessage(b);
                } else {
                    session.sendMessage((TextMessage) frame);
                }
            } catch (Exception e) {
                // The decorator closes the session itself when its buffer/time limit trips.
                log.debug("[SFU] Relaying to signal client failed: {}", e.toString());
            }
        }
    }
}
