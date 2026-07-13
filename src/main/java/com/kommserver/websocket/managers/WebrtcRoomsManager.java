package com.kommserver.websocket.managers;

import com.google.gson.Gson;
import com.kommserver.websocket.messages.UserSessionEntry;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.ChannelJoinPayload;
import com.kommserver.websocket.messages.payloads.SoundboardPlayingPayload;
import com.kommserver.websocket.messages.payloads.VoiceConnectedUsersUpdatePayload;
import com.kommserver.websocket.senders.HubMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebrtcRoomsManager {

    private final Gson gson;
    private final HubMessageSender hubMessageSender;

    public record ChannelRef(UUID serverId, UUID channelId) {}

    // serverId → channelId → userId → VoiceSessionEntry
    private final Map<UUID, Map<UUID, Map<UUID, UserSessionEntry>>> registry = new ConcurrentHashMap<>();

    // userId → destinationChannelId — granted when a privileged user moves someone via MOVE_MEMBER.
    // Consumed (removed) on first matching CHANNEL_JOIN, allowing the target to bypass JOIN_VOICE.
    private final Map<UUID, UUID> pendingForcedJoins = new ConcurrentHashMap<>();

    // streamerUserId → set of viewer userIds currently watching that stream
    private final Map<UUID, Set<UUID>> streamWatchers = new ConcurrentHashMap<>();

    // ── Active soundboard plays ───────────────────────────────────────────────
    // channelId → userId → (soundboardId, startedAt millis)
    // Used to replay in-progress sounds to users who join mid-play.
    private record ActivePlay(UUID soundboardId, long startedAt) {}
    private final Map<UUID, Map<UUID, ActivePlay>> channelActivePlays = new ConcurrentHashMap<>();

    /** Record that userId started playing soundboardId in channelId. */
    public void recordSoundboardPlay(UUID channelId, UUID userId, UUID soundboardId) {
        channelActivePlays
                .computeIfAbsent(channelId, k -> new ConcurrentHashMap<>())
                .put(userId, new ActivePlay(soundboardId, System.currentTimeMillis()));
    }

    /** Remove any active play for this user in this channel (stop / leave). */
    public void clearSoundboardPlay(UUID channelId, UUID userId) {
        Map<UUID, ActivePlay> plays = channelActivePlays.get(channelId);
        if (plays != null) {
            plays.remove(userId);
            if (plays.isEmpty()) channelActivePlays.remove(channelId);
        }
    }

    /**
     * Returns sounds that are still considered in-flight in channelId.
     * Plays older than {@code maxAgeMs} are excluded (they've almost certainly finished).
     */
    public List<SoundboardPlayingPayload> getActiveSoundboardPlays(UUID channelId, long maxAgeMs) {
        Map<UUID, ActivePlay> plays = channelActivePlays.get(channelId);
        if (plays == null || plays.isEmpty()) return List.of();
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        return plays.entrySet().stream()
                .filter(e -> e.getValue().startedAt() >= cutoff)
                .map(e -> SoundboardPlayingPayload.builder()
                        .soundboardId(e.getValue().soundboardId())
                        .userId(e.getKey())
                        .build())
                .toList();
    }

    public void join(UUID serverId, UUID userId, WebSocketSession session, ChannelJoinPayload channelJoinPayload,
                     boolean serverMicEnabled, boolean serverSpeakerEnabled, boolean pokesEnabled) {
        registry
                .computeIfAbsent(serverId,  k -> new ConcurrentHashMap<>())
                .computeIfAbsent(channelJoinPayload.getChannelId(), k -> new ConcurrentHashMap<>())
                .put(userId, UserSessionEntry.builder()
                        .userId(userId)
                        .channelId(channelJoinPayload.getChannelId())
                        .serverId(serverId)
                        .session(session)
                        .micEnabled(channelJoinPayload.isMicEnabled())
                        .speakerEnabled(channelJoinPayload.isSpeakerEnabled())
                        .serverMicEnabled(serverMicEnabled)
                        .serverSpeakerEnabled(serverSpeakerEnabled)
                        .pokesEnabled(pokesEnabled)
                        .build());

        notifyHubActiveUsers(serverId);
    }

    public void updatePokesEnabled(UUID serverId, UUID channelId, UUID userId, boolean pokesEnabled) {
        UserSessionEntry entry = getEntry(serverId, channelId, userId);
        if (entry != null) entry.setPokesEnabled(pokesEnabled);
    }

    private void notifyHubActiveUsers(UUID serverId) {
        int count = getSessionsInServer(serverId).size();
        hubMessageSender.send(WsMessageType.VOCIE_CONNECTED_USERS_UPDATE,
                VoiceConnectedUsersUpdatePayload.builder()
                        .serverId(serverId)
                        .activeUsers(count)
                        .build());
    }

    public boolean leave(UUID serverId, UUID channelId, UUID userId) {
        log.debug("User {} has left the voice channel {} of {}", userId, channelId, serverId);
        Map<UUID, Map<UUID, UserSessionEntry>> serverMap = registry.get(serverId);
        if (serverMap == null) return true;

        Map<UUID, UserSessionEntry> channelMap = serverMap.get(channelId);
        if (channelMap == null) return true;

        channelMap.remove(userId);
        clearSoundboardPlay(channelId, userId);

        boolean empty = channelMap.isEmpty();
        if (empty) {
            serverMap.remove(channelId);
            if (serverMap.isEmpty()) registry.remove(serverId);
        }
        notifyHubActiveUsers(serverId);
        return empty;
    }

    public void broadcastToChannel(UUID serverId, UUID channelId, WsMessage message) {
        String json = gson.toJson(message);
        registry
                .getOrDefault(serverId, Map.of())
                .getOrDefault(channelId, Map.of())
                .values().forEach(entry -> {
                    WebSocketSession s = entry.getSession();
                    if (s.isOpen()) {
                        try {
                            s.sendMessage(new TextMessage(json));
                        } catch (Exception e) {
                            log.error("Failed to broadcast to session={}", s.getId(), e);
                        }
                    }
                });
    }

    /**
     * Removes all users from the given channel and clears its soundboard state.
     * Returns the evicted entries so the caller can send stream-end notifications.
     * The LiveKit room must be deleted separately by the caller.
     */
    public Collection<UserSessionEntry> evictChannel(UUID serverId, UUID channelId) {
        Map<UUID, Map<UUID, UserSessionEntry>> serverMap = registry.get(serverId);
        if (serverMap == null) return List.of();
        Map<UUID, UserSessionEntry> channelMap = serverMap.remove(channelId);
        if (channelMap == null) return List.of();
        if (serverMap.isEmpty()) registry.remove(serverId);
        channelActivePlays.remove(channelId);
        notifyHubActiveUsers(serverId);
        return List.copyOf(channelMap.values());
    }

    public List<ChannelRef> evict(UUID userId) {
        List<ChannelRef> emptyChannels = new ArrayList<>();
        registry.forEach((serverId, serverMap) ->
                serverMap.forEach((channelId, channelMap) -> {
                    if (channelMap.remove(userId) != null) {
                        clearSoundboardPlay(channelId, userId);
                        if (channelMap.isEmpty()) {
                            emptyChannels.add(new ChannelRef(serverId, channelId));
                            serverMap.remove(channelId);
                        }
                    }
                })
        );
        emptyChannels.forEach(ref -> notifyHubActiveUsers(ref.serverId()));
        return emptyChannels;
    }

    public void updateMic(UUID serverId, UUID channelId, UUID userId, boolean mic) {
        UserSessionEntry entry = getEntry(serverId, channelId, userId);
        if (entry != null) entry.setMicEnabled(mic);
    }

    public void updateSpeaker(UUID serverId, UUID channelId, UUID userId, boolean speaker) {
        UserSessionEntry entry = getEntry(serverId, channelId, userId);
        if (entry != null) entry.setSpeakerEnabled(speaker);
    }

    public void updateScreenSharing(UUID serverId, UUID channelId, UUID userId, boolean screenSharing) {
        UserSessionEntry entry = getEntry(serverId, channelId, userId);
        if (entry != null) entry.setScreenSharingEnabled(screenSharing);
    }

    public void updateServerMic(UUID serverId, UUID channelId, UUID userId, boolean serverMicEnabled) {
        UserSessionEntry entry = getEntry(serverId, channelId, userId);
        if (entry != null) entry.setServerMicEnabled(serverMicEnabled);
    }

    public void updateServerSpeaker(UUID serverId, UUID channelId, UUID userId, boolean serverSpeakerEnabled) {
        UserSessionEntry entry = getEntry(serverId, channelId, userId);
        if (entry != null) entry.setServerSpeakerEnabled(serverSpeakerEnabled);
    }

    public UserSessionEntry getEntry(UUID serverId, UUID channelId, UUID userId) {
        return registry
                .getOrDefault(serverId, Map.of())
                .getOrDefault(channelId, Map.of())
                .get(userId);
    }

    /**
     * Returns a snapshot of all entries in a channel — used to send
     * the current voice state to a newly connected client.
     */
    public Collection<UserSessionEntry> getEntriesInChannel(UUID serverId, UUID channelId) {
        return registry
                .getOrDefault(serverId, Map.of())
                .getOrDefault(channelId, Map.of())
                .values();
    }

    public Collection<WebSocketSession> getSessionsInServer(UUID serverId) {
        Map<UUID, Map<UUID, UserSessionEntry>> serverMap = registry.get(serverId);
        if (serverMap == null) return List.of();
        return serverMap.values().stream()
                .flatMap(channelMap -> channelMap.values().stream())
                .map(UserSessionEntry::getSession)
                .toList();
    }

    public Set<UUID> getUsersInChannel(UUID serverId, UUID channelId) {
        return registry
                .getOrDefault(serverId, Map.of())
                .getOrDefault(channelId, Map.of())
                .keySet();
    }

    public ChannelRef getCurrentRoom(UUID userId) {
        for (var serverEntry : registry.entrySet()) {
            for (var channelEntry : serverEntry.getValue().entrySet()) {
                if (channelEntry.getValue().containsKey(userId)) {
                    return new ChannelRef(serverEntry.getKey(), channelEntry.getKey());
                }
            }
        }
        return null;
    }

    public ChannelRef getCurrentRoomInServer(UUID serverId, UUID userId) {
        Map<UUID, Map<UUID, UserSessionEntry>> serverMap = registry.get(serverId);
        if (serverMap == null) return null;
        for (var channelEntry : serverMap.entrySet()) {
            if (channelEntry.getValue().containsKey(userId)) {
                return new ChannelRef(serverId, channelEntry.getKey());
            }
        }
        return null;
    }

    public void setPendingForcedJoin(UUID userId, UUID channelId) {
        pendingForcedJoins.put(userId, channelId);
    }

    /** Atomically checks and removes a pending forced join for this user+channel. Returns true if one existed. */
    public boolean consumePendingForcedJoin(UUID userId, UUID channelId) {
        UUID pending = pendingForcedJoins.get(userId);
        if (channelId.equals(pending)) {
            pendingForcedJoins.remove(userId);
            return true;
        }
        return false;
    }

    public void clearPendingForcedJoin(UUID userId) {
        pendingForcedJoins.remove(userId);
    }

    // ── Stream watcher tracking ───────────────────────────────────────────────

    public int addWatcher(UUID streamerUserId, UUID watcherUserId) {
        Set<UUID> watchers = streamWatchers.computeIfAbsent(streamerUserId, k -> ConcurrentHashMap.newKeySet());
        watchers.add(watcherUserId);
        return watchers.size();
    }

    public int removeWatcher(UUID streamerUserId, UUID watcherUserId) {
        Set<UUID> watchers = streamWatchers.get(streamerUserId);
        if (watchers == null) return 0;
        watchers.remove(watcherUserId);
        int remaining = watchers.size();
        if (remaining == 0) streamWatchers.remove(streamerUserId);
        return remaining;
    }

    public void clearWatchersForStreamer(UUID streamerUserId) {
        streamWatchers.remove(streamerUserId);
    }

    /** Returns streamerUserId → new count for each stream this user was watching. */
    public Map<UUID, Integer> removeAsWatcher(UUID watcherUserId) {
        Map<UUID, Integer> updates = new HashMap<>();
        streamWatchers.forEach((streamerId, watchers) -> {
            if (watchers.remove(watcherUserId)) {
                updates.put(streamerId, watchers.size());
            }
        });
        streamWatchers.entrySet().removeIf(e -> e.getValue().isEmpty());
        return updates;
    }

    public boolean isUserInChannel(UUID serverId, UUID channelId, UUID userId) {
        return registry
                .getOrDefault(serverId, Map.of())
                .getOrDefault(channelId, Map.of())
                .containsKey(userId);
    }
}