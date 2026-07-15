package com.kommserver.service;

import com.google.gson.Gson;
import com.kommserver.model.db.Permission;
import com.kommserver.model.dto.summary.ChannelSummary;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.ChannelCreatedPayload;
import com.kommserver.websocket.messages.payloads.ChannelDeletedPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pushes per-user CHANNEL_CREATED / CHANNEL_DELETED messages when a permission change alters a
 * user's effective VIEW_CHANNEL, so connected clients show/hide channels immediately.
 *
 * Usage pattern: take a snapshot before the mutation, apply the mutation (which must evict the
 * "channelPerms" cache), take a snapshot after, then call the matching apply method.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelVisibilityService {

    private final PermissionService permissionService;
    private final ChannelService channelService;
    private final ClientMessageSender clientMessageSender;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final Gson gson;

    /** VIEW_CHANNEL status of every online user for one channel. */
    public Map<UUID, Boolean> snapshotViewChannel(UUID serverId, UUID channelId) {
        Set<UUID> online = clientMessageSender.getOnlineUserIds(serverId);
        Map<UUID, Boolean> snapshot = new HashMap<>();
        for (UUID uid : online) {
            snapshot.put(uid, permissionService.hasInChannel(uid, serverId, channelId, Permission.VIEW_CHANNEL));
        }
        return snapshot;
    }

    /**
     * VIEW_CHANNEL status of one user for every permission-relevant channel in the server.
     * Returns an empty map when the user is offline (nothing needs to be pushed).
     */
    public Map<UUID, Boolean> snapshotUserViewAllChannels(UUID serverId, UUID targetUserId) {
        if (!clientMessageSender.getOnlineUserIds(serverId).contains(targetUserId)) return Map.of();
        Map<UUID, Boolean> snapshot = new HashMap<>();
        channelService.getServerChannels(serverId).forEach((chId, ch) -> {
            if (!isDecoration(ch)) {
                snapshot.put(chId, permissionService.hasInChannel(
                        targetUserId, serverId, chId, Permission.VIEW_CHANNEL));
            }
        });
        return snapshot;
    }

    /** VIEW_CHANNEL status of every online user for every permission-relevant channel. */
    public Map<UUID, Map<UUID, Boolean>> snapshotAllUsersAllChannels(UUID serverId) {
        Map<UUID, Map<UUID, Boolean>> snapshot = new HashMap<>();
        Set<UUID> online = clientMessageSender.getOnlineUserIds(serverId);
        if (online.isEmpty()) return snapshot;
        Map<UUID, ChannelSummary> channels = channelService.getServerChannels(serverId);
        for (UUID uid : online) {
            Map<UUID, Boolean> perChannel = new HashMap<>();
            channels.forEach((chId, ch) -> {
                if (!isDecoration(ch)) {
                    perChannel.put(chId, permissionService.hasInChannel(
                            uid, serverId, chId, Permission.VIEW_CHANNEL));
                }
            });
            snapshot.put(uid, perChannel);
        }
        return snapshot;
    }

    /** Diffs one-channel snapshots (all users) and notifies each user whose visibility changed. */
    public void applyViewChannelChanges(UUID serverId, UUID channelId,
                                        Map<UUID, Boolean> before, Map<UUID, Boolean> after) {
        Set<UUID> allUsers = new HashSet<>(before.keySet());
        allUsers.addAll(after.keySet());
        for (UUID uid : allUsers) {
            sendUserViewChannelChange(serverId, channelId, uid,
                    before.getOrDefault(uid, false), after.getOrDefault(uid, false));
        }
    }

    /** Diffs one-user snapshots (all channels) and notifies the user per changed channel. */
    public void applyUserViewChanges(UUID serverId, UUID targetUserId,
                                     Map<UUID, Boolean> before, Map<UUID, Boolean> after) {
        Set<UUID> allChannels = new HashSet<>(before.keySet());
        allChannels.addAll(after.keySet());
        for (UUID chId : allChannels) {
            sendUserViewChannelChange(serverId, chId, targetUserId,
                    before.getOrDefault(chId, false), after.getOrDefault(chId, false));
        }
    }

    /** Diffs all-users/all-channels snapshots and notifies every affected user. */
    public void applyAllUserViewChanges(UUID serverId,
                                        Map<UUID, Map<UUID, Boolean>> before,
                                        Map<UUID, Map<UUID, Boolean>> after) {
        Set<UUID> allUsers = new HashSet<>(before.keySet());
        allUsers.addAll(after.keySet());
        for (UUID uid : allUsers) {
            applyUserViewChanges(serverId, uid,
                    before.getOrDefault(uid, Map.of()), after.getOrDefault(uid, Map.of()));
        }
    }

    /** Sends CHANNEL_DELETED or CHANNEL_CREATED to a specific user when their visibility changed. */
    public void sendUserViewChannelChange(UUID serverId, UUID channelId, UUID userId,
                                          boolean hadView, boolean hasView) {
        if (hadView == hasView) return;
        if (!hasView) {
            // Never hide a channel from someone currently connected to it in voice
            if (webrtcRoomsManager.isUserInChannel(serverId, channelId, userId)) return;
            WsMessage msg = WsMessage.builder()
                    .type(WsMessageType.CHANNEL_DELETED)
                    .payload(ChannelDeletedPayload.builder()
                            .channelId(channelId).serverId(serverId).build())
                    .build();
            clientMessageSender.sendToUser(serverId, userId, gson.toJson(msg));
        } else {
            channelService.getChannelById(serverId, channelId).ifPresent(ch -> {
                WsMessage msg = WsMessage.builder()
                        .type(WsMessageType.CHANNEL_CREATED)
                        .payload(ChannelCreatedPayload.builder()
                                .channelId(ch.getChannelId())
                                .serverId(ch.getServerId())
                                .channelName(ch.getChannelName())
                                .channelType(ch.getChannelType())
                                .description(ch.getDescription())
                                .position(ch.getPosition())
                                .icon(ch.getIcon())
                                .build())
                        .build();
                clientMessageSender.sendToUser(serverId, userId, gson.toJson(msg));
            });
        }
    }

    private boolean isDecoration(ChannelSummary ch) {
        ChannelSummary.ChannelType t = ch.getChannelType();
        return t == ChannelSummary.ChannelType.SPACER
                || t == ChannelSummary.ChannelType.DIVIDER
                || t == ChannelSummary.ChannelType.TITLE;
    }
}
