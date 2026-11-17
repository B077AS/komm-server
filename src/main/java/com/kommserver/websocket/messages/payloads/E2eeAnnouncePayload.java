package com.kommserver.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Voice E2EE: a member's ephemeral public key for the current call.
 * Inbound only {@code publicKey} is read; the server stamps {@code channelId}
 * and {@code userId} before relaying it to the rest of the voice channel.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class E2eeAnnouncePayload {
    private UUID channelId;
    private UUID userId;
    /** Base64 X.509-encoded X25519 public key. Opaque to the server. */
    private String publicKey;
}
