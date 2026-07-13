package com.kommserver.service;

import com.google.gson.Gson;
import com.kommserver.model.db.Channel;
import com.kommserver.model.db.Server;
import com.kommserver.model.db.ServerSoundboard;
import com.kommserver.repository.*;
import com.kommserver.sfu.LiveKitTokenService;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.ForceDisconnectPayload;
import com.kommserver.websocket.messages.payloads.ServerDeletedPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import com.kommserver.websocket.senders.HubMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Installation-side server deletion. The hub is the source of truth and drives this via
 * {@code SERVER_DELETE_NOTIFICATION} (or sync-recap on reconnect).
 *
 * <p>{@link #markForDeletion} flips a persisted {@code pending_deletion} flag and disconnects any
 * connected members. {@link #purgeServer} (run by {@link ServerPurgeService} on a schedule and on
 * startup) deletes the heavy data in batches, then reports {@code SERVER_DELETION_COMPLETE} back to
 * the hub so it can finalize. Because the flag is persisted, a restart resumes the purge.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServerDeletionService {

    /** Batch size for IN-clause deletes of message-scoped child rows. */
    private static final int CHUNK = 500;

    private final ServerRepository serverRepository;
    private final ServerMemberRepository serverMemberRepository;
    private final ServerBanRepository serverBanRepository;
    private final ServerRolePermissionRepository serverRolePermissionRepository;
    private final ServerCustomRoleRepository serverCustomRoleRepository;
    private final ServerMemberCustomRoleRepository serverMemberCustomRoleRepository;
    private final ServerSoundboardRepository serverSoundboardRepository;
    private final ChannelRepository channelRepository;
    private final MessageRepository messageRepository;
    private final MessageAttachmentRepository messageAttachmentRepository;
    private final MessageReactionRepository messageReactionRepository;

    private final PermissionService permissionService;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final LiveKitTokenService liveKitTokenService;
    private final ClientMessageSender clientMessageSender;
    private final HubMessageSender hubMessageSender;
    private final Gson gson;

    // ── Flag + disconnect ────────────────────────────────────────────────────────

    /**
     * Flag a server for deletion and disconnect anyone still connected. Idempotent. If the server is
     * already gone (purged earlier), re-acks the hub so a redelivered notification is satisfied.
     */
    @Transactional
    public void markForDeletion(UUID serverId) {
        Server server = serverRepository.findById(serverId).orElse(null);
        if (server == null) {
            // Already purged — confirm to the hub again in case the previous ack was lost.
            notifyCompletion(serverId);
            return;
        }

        if (!Boolean.TRUE.equals(server.getPendingDeletion())) {
            server.setPendingDeletion(true);
            server.setDeletionRequestedAt(LocalDateTime.now());
            serverRepository.save(server);
        }

        String forceMsg = gson.toJson(WsMessage.builder()
                .type(WsMessageType.FORCE_DISCONNECT)
                .payload(ForceDisconnectPayload.builder().reason("SERVER_DELETED").build())
                .build());
        Set<UUID> online = clientMessageSender.getOnlineUserIds(serverId);
        for (UUID userId : online) {
            clientMessageSender.sendToUser(serverId, userId, forceMsg);
            clientMessageSender.closeUserSession(serverId, userId);
        }

        log.info("SERVER marked for deletion: serverId={} ({} member(s) disconnected)", serverId, online.size());
    }

    // ── Purge ──────────────────────────────────────────────────────────────────

    @Transactional
    public void purgeServer(UUID serverId) {
        log.info("Purging data for serverId={}", serverId);

        List<Channel> channels = channelRepository.findChannelsByServerId(serverId);
        for (Channel channel : channels) {
            UUID channelId = channel.getChannelId();
            webrtcRoomsManager.evictChannel(serverId, channelId);
            liveKitTokenService.deleteRoom(serverId, channelId);
            permissionService.deleteChannelPermissions(channelId);
        }

        // Messages + their attachments (files on disk) and reactions, in batches.
        List<UUID> messageIds = messageRepository.findMessageIdsByServerId(serverId);
        for (int i = 0; i < messageIds.size(); i += CHUNK) {
            List<UUID> chunk = messageIds.subList(i, Math.min(i + CHUNK, messageIds.size()));
            messageAttachmentRepository.findByMessageIdIn(chunk)
                    .forEach(a -> deleteFile(a.getFilePath()));
            messageAttachmentRepository.deleteByMessageIdIn(chunk);
            messageReactionRepository.deleteByMessageIdIn(chunk);
        }
        messageRepository.deleteByServerId(serverId);

        channelRepository.deleteAll(channels);

        // Roles / permissions.
        serverMemberCustomRoleRepository.deleteByServerId(serverId);
        serverCustomRoleRepository.deleteAll(serverCustomRoleRepository.findByServerIdOrderByPosition(serverId));
        serverRolePermissionRepository.deleteByServerId(serverId);

        // Soundboards (files on disk).
        List<ServerSoundboard> soundboards = serverSoundboardRepository.findByServerIdOrderBySlotIndex(serverId);
        soundboards.forEach(s -> deleteFile(s.getFilePath()));
        serverSoundboardRepository.deleteAll(soundboards);

        serverBanRepository.deleteByServerId(serverId);
        serverMemberRepository.deleteByServerId(serverId);

        serverRepository.deleteById(serverId);
        notifyCompletion(serverId);
        log.info("Purge complete for serverId={}", serverId);
    }

    /** Tell the hub the purge is done so it can finalize (hard-delete its rows). */
    private void notifyCompletion(UUID serverId) {
        hubMessageSender.send(WsMessageType.SERVER_DELETION_COMPLETE,
                ServerDeletedPayload.builder().serverId(serverId).build());
    }

    private void deleteFile(String path) {
        if (path == null || path.isBlank()) return;
        try {
            Files.deleteIfExists(Paths.get(path));
        } catch (Exception e) {
            log.warn("Could not delete file {}: {}", path, e.getMessage());
        }
    }
}
