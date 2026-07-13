package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.Channel;
import com.kommserver.model.db.Permission;
import com.kommserver.repository.ChannelRepository;
import com.kommserver.repository.ServerMemberRepository;
import com.kommserver.service.PermissionService;
import com.kommserver.sfu.LiveKitTokenService;
import com.kommserver.websocket.senders.ClientMessageSender;
import com.kommserver.websocket.managers.ClientSessionManager;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.messages.UserSessionEntry;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.ChannelJoinDeniedPayload;
import com.kommserver.websocket.messages.payloads.ChannelJoinPayload;
import com.kommserver.websocket.messages.payloads.SoundboardPlayingPayload;
import com.kommserver.websocket.messages.payloads.StreamEndedPayload;
import com.kommserver.websocket.messages.payloads.StreamViewerCountPayload;
import com.kommserver.websocket.messages.payloads.UserJoinedChannelPayload;
import com.kommserver.websocket.messages.payloads.UserLeftChannelPayload;
import com.kommserver.websocket.messages.payloads.UserScreenSharePayload;
import com.kommserver.websocket.messages.payloads.VoiceTokenPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelJoinHandler implements ClientInboundMessageHandler {

    private final Gson gson;
    private final LiveKitTokenService liveKitTokenService;
    private final ClientMessageSender clientMessageSender;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final ChannelRepository channelRepository;
    private final ServerMemberRepository serverMemberRepository;
    private final PermissionService permissionService;

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNEL_JOIN;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        ChannelJoinPayload joinPayload = gson.fromJson(payload, ChannelJoinPayload.class);
        UUID userId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);
        UUID channelId = joinPayload.getChannelId();

        Channel channel = channelRepository.findById(channelId).orElse(null);
        if (channel == null || !channel.getServerId().equals(serverId)) {
            log.warn("CHANNEL_JOIN denied: channelId={} does not exist or does not belong to serverId={} (userId={})",
                    channelId, serverId, userId);
            sendJoinDenied(session, channelId, "Channel not found");
            return;
        }

        if (!serverMemberRepository.existsByServerIdAndUserId(serverId, userId)) {
            log.warn("CHANNEL_JOIN denied: userId={} is not a member of serverId={}", userId, serverId);
            sendJoinDenied(session, channelId, "Not a member of this server");
            return;
        }

        // Forced moves (via MOVE_MEMBER) bypass JOIN_VOICE — the token is consumed exactly once.
        boolean isForced = webrtcRoomsManager.consumePendingForcedJoin(userId, channelId);
        if (!isForced && !permissionService.hasInChannel(userId, serverId, channelId, Permission.JOIN_VOICE)) {
            log.warn("CHANNEL_JOIN denied: userId={} missing JOIN_VOICE for channelId={}", userId, channelId);
            sendJoinDenied(session, channelId, "Missing permission: JOIN_VOICE");
            return;
        }

        // If the user is already in another channel on this server, force-leave them server-side.
        WebrtcRoomsManager.ChannelRef current = webrtcRoomsManager.getCurrentRoomInServer(serverId, userId);
        if (current != null && !current.channelId().equals(channelId)) {
            // Capture streaming state before the entry is removed so we can tear down any live
            // stream the user was broadcasting from the channel they're being moved out of.
            UserSessionEntry leavingEntry = webrtcRoomsManager.getEntry(serverId, current.channelId(), userId);
            boolean wasStreaming = leavingEntry != null && leavingEntry.isScreenSharingEnabled();

            boolean roomEmpty = webrtcRoomsManager.leave(serverId, current.channelId(), userId);
            if (roomEmpty) {
                liveKitTokenService.deleteRoom(serverId, current.channelId());
            }
            WsMessage leftBroadcast = WsMessage.builder()
                    .type(WsMessageType.USER_LEFT_CHANNEL)
                    .payload(gson.toJsonTree(UserLeftChannelPayload.builder()
                            .channelId(current.channelId())
                            .userId(userId)
                            .build()).getAsJsonObject())
                    .build();
            clientMessageSender.broadcastToServer(serverId, gson.toJson(leftBroadcast));

            // The moved user stops watching whatever they were viewing; update those counts.
            Map<UUID, Integer> watchUpdates = webrtcRoomsManager.removeAsWatcher(userId);
            watchUpdates.forEach((streamerId, count) ->
                    clientMessageSender.broadcastToServer(serverId, gson.toJson(
                            WsMessage.builder()
                                    .type(WsMessageType.STREAM_VIEWER_COUNT)
                                    .payload(StreamViewerCountPayload.builder()
                                            .streamerUserId(streamerId)
                                            .count(count)
                                            .build())
                                    .build())));

            // If the moved user was streaming, the move tears their stream down (correctly).
            // Mirror ChannelLeaveHandler so every client clears the live indicator: drop the
            // screen-share icon (USER_SCREEN_SHARE false), zero the viewer count and close any
            // open viewers (STREAM_ENDED). Without this the move never emits these, leaving a
            // stale "live" indicator on the moved user's card.
            if (wasStreaming) {
                webrtcRoomsManager.clearWatchersForStreamer(userId);
                clientMessageSender.broadcastToServer(serverId, gson.toJson(
                        WsMessage.builder()
                                .type(WsMessageType.USER_SCREEN_SHARE)
                                .payload(UserScreenSharePayload.builder()
                                        .userId(userId)
                                        .sharing(false)
                                        .audioEnabled(false)
                                        .build())
                                .build()));
                clientMessageSender.broadcastToServer(serverId, gson.toJson(
                        WsMessage.builder()
                                .type(WsMessageType.STREAM_VIEWER_COUNT)
                                .payload(StreamViewerCountPayload.builder()
                                        .streamerUserId(userId)
                                        .count(0)
                                        .build())
                                .build()));
                clientMessageSender.broadcastToServer(serverId, gson.toJson(
                        WsMessage.builder()
                                .type(WsMessageType.STREAM_ENDED)
                                .payload(StreamEndedPayload.builder()
                                        .streamerUserId(userId)
                                        .build())
                                .build()));
            }

            log.info("CHANNEL_JOIN: server-side forced leave userId={} from channelId={} (wasStreaming={})",
                    userId, current.channelId(), wasStreaming);
        }

        com.kommserver.model.db.ServerMember member =
                serverMemberRepository.findByServerIdAndUserId(serverId, userId).orElse(null);
        boolean serverMicEnabled = member == null || member.isServerMicEnabled();
        boolean serverSpeakerEnabled = member == null || member.isServerSpeakerEnabled();
        boolean pokesEnabled = member == null || member.isPokesEnabled();

        webrtcRoomsManager.join(serverId, userId, session, joinPayload, serverMicEnabled, serverSpeakerEnabled, pokesEnabled);

        WsMessage tokenResponse = WsMessage.builder()
                .type(WsMessageType.CHANNEL_TOKEN)
                .payload(gson.toJsonTree(VoiceTokenPayload.builder()
                        .livekitUrl(liveKitTokenService.getLiveKitUrl())
                        .token(liveKitTokenService.issueToken(serverId, channelId, userId))
                        .build()).getAsJsonObject())
                .build();

        try {
            session.sendMessage(new TextMessage(gson.toJson(tokenResponse)));
        } catch (Exception e) {
            log.error("Failed to send CHANNEL_TOKEN to userId={}", userId, e);
        }

        WsMessage broadcast = WsMessage.builder()
                .type(WsMessageType.USER_JOINED_CHANNEL)
                .payload(gson.toJsonTree(UserJoinedChannelPayload.builder()
                        .channelId(channelId)
                        .userId(userId)
                        .micEnabled(joinPayload.isMicEnabled())
                        .speakerEnabled(joinPayload.isSpeakerEnabled())
                        .serverMicEnabled(serverMicEnabled)
                        .serverSpeakerEnabled(serverSpeakerEnabled)
                        .pokesEnabled(pokesEnabled)
                        .build()).getAsJsonObject())
                .build();

        clientMessageSender.broadcastToServer(serverId, gson.toJson(broadcast));

        // Replay any in-progress soundboard sounds to the joining user.
        // Sounds older than 3 minutes are ignored (they've almost certainly finished).
        for (SoundboardPlayingPayload play : webrtcRoomsManager.getActiveSoundboardPlays(channelId, 3 * 60 * 1000L)) {
            WsMessage playMsg = WsMessage.builder()
                    .type(WsMessageType.SOUNDBOARD_PLAYING)
                    .payload(gson.toJsonTree(play).getAsJsonObject())
                    .build();
            try {
                session.sendMessage(new TextMessage(gson.toJson(playMsg)));
            } catch (Exception e) {
                log.warn("Failed to replay SOUNDBOARD_PLAYING to userId={}: {}", userId, e.getMessage());
            }
        }

        log.info("CHANNEL_JOIN: userId={} joined serverId={} channelId={}", userId, serverId, channelId);
    }

    private void sendJoinDenied(WebSocketSession session, UUID channelId, String reason) {
        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.CHANNEL_JOIN_DENIED)
                .payload(gson.toJsonTree(ChannelJoinDeniedPayload.builder()
                        .channelId(channelId)
                        .reason(reason)
                        .build()).getAsJsonObject())
                .build();
        try {
            session.sendMessage(new TextMessage(gson.toJson(msg)));
        } catch (Exception e) {
            log.error("Failed to send CHANNEL_JOIN_DENIED to session={}", session.getId(), e);
        }
    }
}
