package com.kommserver.websocket.service;

import com.google.gson.Gson;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.UserPingUpdatePayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-user RTT samples (piggybacked on their own PING frames) and
 * notifies observers in real time.
 *
 * <p>No probing needed: each client already reports its self-measured RTT via
 * {@code PingPayload.lastRttMs}.  The server just stores a ring buffer per user
 * and fans out {@code USER_PING_UPDATE} frames to whoever is subscribed.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPingTrackerService {

    private static final int HISTORY_SIZE = 60;

    private final ClientMessageSender clientMessageSender;
    private final Gson gson;

    // (serverId, targetUserId) → ring buffer of RTT samples
    private final Map<String, LinkedList<Integer>> histories = new ConcurrentHashMap<>();

    // (serverId, targetUserId) → most recently self-reported missed-heartbeat rate (0-100)
    private final Map<String, Integer> lossByKey = new ConcurrentHashMap<>();

    // (serverId, targetUserId) → set of observer userIds
    private final Map<String, Set<UUID>> subscribers = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called by {@link com.kommserver.websocket.handlers.PingHandler} whenever a
     * client sends a PING that carries a valid {@code lastRttMs}. {@code lossPct}
     * is that same client's self-measured missed-heartbeat rate.
     */
    public void record(UUID serverId, UUID userId, int rttMs, int lossPct) {
        String key = key(serverId, userId);

        LinkedList<Integer> buf = histories.computeIfAbsent(key, k -> new LinkedList<>());
        synchronized (buf) {
            if (buf.size() >= HISTORY_SIZE) buf.removeFirst();
            buf.addLast(rttMs);
        }
        lossByKey.put(key, lossPct);

        Set<UUID> obs = subscribers.get(key);
        if (obs == null || obs.isEmpty()) return;

        String msg = buildUpdate(userId, rttMs, lossPct);
        obs.forEach(observerId -> clientMessageSender.sendToUser(serverId, observerId, msg));
        log.debug("Relayed ping {}ms (loss={}%) for userId={} to {} observer(s)", rttMs, lossPct, userId, obs.size());
    }

    /**
     * Subscribes {@code observerId} to live RTT updates for {@code targetUserId}.
     * Immediately flushes the buffered history to the observer.
     */
    public void subscribe(UUID serverId, UUID observerId, UUID targetUserId) {
        String key = key(serverId, targetUserId);
        subscribers.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(observerId);

        // Send buffered history immediately so the popup is never cold.
        // Loss is a slow-moving aggregate (not tracked per historical sample), so the
        // latest known value is attached to every replayed sample.
        LinkedList<Integer> buf = histories.get(key);
        if (buf != null) {
            List<Integer> snapshot;
            synchronized (buf) { snapshot = new ArrayList<>(buf); }
            int lossPct = lossByKey.getOrDefault(key, 0);
            snapshot.forEach(rttMs ->
                    clientMessageSender.sendToUser(serverId, observerId, buildUpdate(targetUserId, rttMs, lossPct)));
            log.debug("Flushed {} history samples for userId={} to observer={}", snapshot.size(), targetUserId, observerId);
        }

        log.debug("Subscribed observer={} to ping of target={} on server={}", observerId, targetUserId, serverId);
    }

    public void unsubscribe(UUID serverId, UUID observerId, UUID targetUserId) {
        String key = key(serverId, targetUserId);
        Set<UUID> obs = subscribers.get(key);
        if (obs != null) {
            obs.remove(observerId);
            if (obs.isEmpty()) subscribers.remove(key);
        }
        log.debug("Unsubscribed observer={} from ping of target={}", observerId, targetUserId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildUpdate(UUID targetUserId, int pingMs, int lossPct) {
        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.USER_PING_UPDATE)
                .payload(gson.toJsonTree(UserPingUpdatePayload.builder()
                        .targetUserId(targetUserId)
                        .pingMs(pingMs)
                        .lossPct(lossPct).build()).getAsJsonObject())
                .build();
        return gson.toJson(msg);
    }

    private static String key(UUID serverId, UUID userId) {
        return serverId + ":" + userId;
    }
}
