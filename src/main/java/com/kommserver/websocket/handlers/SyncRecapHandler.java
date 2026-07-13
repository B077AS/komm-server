package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.*;
import com.kommserver.repository.*;
import com.kommserver.service.PermissionService;
import com.kommserver.service.ServerDeletionService;
import com.kommserver.websocket.interfaces.HubInboundMessageHandler;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.ServerMemberPayload;
import com.kommserver.websocket.messages.payloads.ServerPayload;
import com.kommserver.websocket.messages.payloads.SyncRecapPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class SyncRecapHandler implements HubInboundMessageHandler {

    private final Gson gson;
    private final ServerRepository serverRepository;
    private final ChannelRepository channelRepository;
    private final ServerMemberRepository serverMemberRepository;
    private final PermissionService permissionService;
    private final ServerDeletionService serverDeletionService;

    @Override
    public WsMessageType getType() {
        return WsMessageType.SYNC_RECAP;
    }

    @Override
    public void handle(JsonObject payload) {
        SyncRecapPayload recap = gson.fromJson(payload, SyncRecapPayload.class);

        syncServers(recap.getServersList());
        syncMembers(recap.getMembersList());
        applyPendingDeletions(recap.getPendingDeletionServerIds());
    }

    // -------------------------------------------------------------------------
    // Pending deletions
    // -------------------------------------------------------------------------

    private void applyPendingDeletions(List<UUID> pendingDeletionServerIds) {
        if (pendingDeletionServerIds == null || pendingDeletionServerIds.isEmpty()) return;
        for (UUID serverId : pendingDeletionServerIds) {
            serverDeletionService.markForDeletion(serverId);
        }
        log.info("SYNC_RECAP: {} server(s) marked for deletion", pendingDeletionServerIds.size());
    }

    // -------------------------------------------------------------------------
    // Servers
    // -------------------------------------------------------------------------

    private void syncServers(List<ServerPayload> servers) {
        if (servers == null || servers.isEmpty()) {
            log.debug("SYNC_RECAP: no servers to sync, skipping");
            return;
        }

        for (ServerPayload incoming : servers) {
            serverRepository.findById(incoming.getServerId()).ifPresentOrElse(
                    existing -> updateServerIfChanged(existing, incoming),
                    () -> createServer(incoming)
            );
        }

        log.info("SYNC_RECAP: {} server(s) synced", servers.size());
    }

    private void updateServerIfChanged(Server existing, ServerPayload incoming) {
        boolean dirty = false;

        if (!Objects.equals(existing.getServerName(), incoming.getServerName())) {
            existing.setServerName(incoming.getServerName());
            dirty = true;
        }
        if (!Objects.equals(existing.getDescription(), incoming.getDescription())) {
            existing.setDescription(incoming.getDescription());
            dirty = true;
        }
        if (!Objects.equals(existing.getOwnerId(), incoming.getOwnerId())) {
            existing.setOwnerId(incoming.getOwnerId());
            dirty = true;
        }

        if (dirty) {
            serverRepository.save(existing);
            log.debug("SYNC_RECAP: updated server={}", existing.getServerId());
        } else {
            log.debug("SYNC_RECAP: server={} unchanged", existing.getServerId());
        }
    }

    private void createServer(ServerPayload incoming) {
        Server server = Server.builder()
                .serverId(incoming.getServerId())
                .serverName(incoming.getServerName())
                .description(incoming.getDescription())
                .ownerId(incoming.getOwnerId())
                .createdAt(incoming.getCreatedAt())
                .build();

        serverRepository.save(server);
        log.debug("SYNC_RECAP: created server={}", incoming.getServerId());

        createDefaultChannels(server.getServerId());
        permissionService.seedDefaults(server.getServerId());
    }

    private void createDefaultChannels(UUID serverId) {
        Channel generalText = Channel.builder()
                .serverId(serverId)
                .channelName("generalText")
                .channelType(Channel.ChannelType.TEXT)
                .description("General text channel")
                .position(0)
                .build();

        Channel generalVoice = Channel.builder()
                .serverId(serverId)
                .channelName("generalVoice")
                .channelType(Channel.ChannelType.VOICE)
                .description("General voice channel")
                .position(1)
                .build();

        channelRepository.saveAll(List.of(generalText, generalVoice));
        log.debug("SYNC_RECAP: created default channels for server={}", serverId);
    }

    // -------------------------------------------------------------------------
    // Members
    // -------------------------------------------------------------------------

    private void syncMembers(List<ServerMemberPayload> incomingMembers) {
        if (incomingMembers == null) {
            log.debug("SYNC_RECAP: membersList is null, skipping member sync");
            return;
        }

        Map<ServerMember.ServerMemberId, ServerMemberPayload> incomingMap = incomingMembers.stream()
                .collect(Collectors.toMap(
                        m -> memberId(m.getServerId(), m.getUserId()),
                        Function.identity()
                ));

        List<UUID> serverIds = incomingMembers.stream()
                .map(ServerMemberPayload::getServerId)
                .distinct()
                .collect(Collectors.toList());

        List<ServerMember> existingMembers = serverIds.isEmpty()
                ? List.of()
                : serverMemberRepository.findByServerIdIn(serverIds);

        for (ServerMember existing : existingMembers) {
            ServerMember.ServerMemberId key = memberId(existing.getServerId(), existing.getUserId());
            ServerMemberPayload incoming = incomingMap.remove(key);

            if (incoming == null) {
                serverMemberRepository.delete(existing);
                log.debug("SYNC_RECAP: deleted member userId={} from server={}", existing.getUserId(), existing.getServerId());
            } else {
                updateMemberIfChanged(existing, incoming);
            }
        }

        for (ServerMemberPayload newMember : incomingMap.values()) {
            ServerMember entity = ServerMember.builder()
                    .serverId(newMember.getServerId())
                    .userId(newMember.getUserId())
                    .role(newMember.getRole())
                    .joinedAt(newMember.getJoinedAt())
                    .build();

            serverMemberRepository.save(entity);
            log.debug("SYNC_RECAP: created member userId={} in server={}", newMember.getUserId(), newMember.getServerId());
        }

        log.info("SYNC_RECAP: {} member(s) synced", incomingMembers.size());
    }

    private void updateMemberIfChanged(ServerMember existing, ServerMemberPayload incoming) {
        boolean dirty = false;

        if (!Objects.equals(existing.getRole(), incoming.getRole())) {
            existing.setRole(incoming.getRole());
            dirty = true;
        }
        if (!Objects.equals(existing.getJoinedAt(), incoming.getJoinedAt())) {
            existing.setJoinedAt(incoming.getJoinedAt());
            dirty = true;
        }

        if (dirty) {
            serverMemberRepository.save(existing);
            log.debug("SYNC_RECAP: updated member userId={} in server={}", existing.getUserId(), existing.getServerId());
        } else {
            log.debug("SYNC_RECAP: member userId={} in server={} unchanged", existing.getUserId(), existing.getServerId());
        }
    }

    private ServerMember.ServerMemberId memberId(UUID serverId, UUID userId) {
        ServerMember.ServerMemberId id = new ServerMember.ServerMemberId();
        id.setServerId(serverId);
        id.setUserId(userId);
        return id;
    }
}
