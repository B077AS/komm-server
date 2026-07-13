package com.kommserver.sfu;

import io.livekit.server.AccessToken;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.RoomServiceClient;
import livekit.LivekitModels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import retrofit2.Response;

import java.io.IOException;
import java.util.UUID;

/**
 * Thin wrapper around the LiveKit server SDK.
 * <p>
 * Responsibilities:
 * 1. Ensure a LiveKit room exists for a given channel when a user joins.
 * 2. Mint a short-lived JWT access token for that user.
 * <p>
 * The token is handed back to the client over the existing Spring WebSocket.
 * The client then connects directly to LiveKit using it — Spring is never
 * in the audio path.
 * <p>
 * Room naming convention: "<serverId>_<channelId>"
 * Participant identity:    "<userId>"
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveKitTokenService {

    @Value("${sfu.signal-port}")
    private int signalPort;

    /**
     * Token TTL — long enough to survive a session but short enough to rotate.
     */
    private static final long TOKEN_TTL_MS = 6 * 60 * 60 * 1000L; // 6 hours
    //private static final long TOKEN_TTL_MS = 60 * 2000L; // 1 minute

    private final SfuLauncher sfuLauncher;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Creates the LiveKit room for this channel if it doesn't already exist,
     * then returns a signed JWT the client can use to join.
     *
     * @param serverId  Kommserver server UUID
     * @param channelId Kommserver voice channel UUID
     * @param userId    The joining user's UUID (becomes the LiveKit participant identity)
     * @return signed JWT string
     */
    public String issueToken(UUID serverId, UUID channelId, UUID userId) {
        String roomName = roomName(serverId, channelId);
        ensureRoom(roomName);
        return mintToken(roomName, userId.toString(), userId.toString());
    }

    /**
     * The WebSocket URL clients should open for LiveKit signaling.
     */
    public String getLiveKitUrl() {
        return sfuLauncher.getSignalUrl();
    }

    // ── Room management ───────────────────────────────────────────────────────

    private void ensureRoom(String roomName) {
        try {
            RoomServiceClient client = buildRoomClient();
            Response<LivekitModels.Room> response = client.createRoom(roomName).execute();
            if (response.isSuccessful()) {
                log.debug("[SFU] Room '{}' ready.", roomName);
            } else {
                // 409 / duplicate is fine — room already exists
                log.debug("[SFU] createRoom '{}' returned {}: {}", roomName,
                        response.code(), response.message());
            }
        } catch (IOException e) {
            // Non-fatal: LiveKit will create the room lazily when the first participant joins
            log.warn("[SFU] Could not pre-create room '{}': {}", roomName, e.getMessage());
        }
    }

    /**
     * Delete the LiveKit room when the last user leaves a voice channel.
     * Call from your VoiceLeaveHandler / disconnect cleanup.
     */
    public void deleteRoom(UUID serverId, UUID channelId) {
        String roomName = roomName(serverId, channelId);
        try {
            RoomServiceClient client = buildRoomClient();
            client.deleteRoom(roomName).execute();
            log.debug("[SFU] Room '{}' deleted.", roomName);
        } catch (IOException e) {
            log.warn("[SFU] Could not delete room '{}': {}", roomName, e.getMessage());
        }
    }

    // ── Token minting ─────────────────────────────────────────────────────────

    private String mintToken(String roomName, String identity, String name) {
        AccessToken token = new AccessToken(sfuLauncher.getApiKey(), sfuLauncher.getApiSecret());
        token.setIdentity(identity);
        token.setName(name);
        token.setTtl(TOKEN_TTL_MS);
        token.addGrants(new RoomJoin(true), new RoomName(roomName));
        String jwt = token.toJwt();
        log.debug("[SFU] Token issued for identity={} room={}", identity, roomName);
        return jwt;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RoomServiceClient buildRoomClient() {
        return RoomServiceClient.createClient(
                "http://localhost:"+signalPort,
                sfuLauncher.getApiKey(),
                sfuLauncher.getApiSecret()
        );
    }

    private static String roomName(UUID serverId, UUID channelId) {
        return serverId + "_" + channelId;
    }
}