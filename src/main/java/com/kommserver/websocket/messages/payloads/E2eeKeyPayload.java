package com.kommserver.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Voice E2EE: one member's media key, sealed for one other member of the same
 * voice channel. Everything except the routing fields is opaque to the server;
 * {@code channelId} and {@code fromUserId} are stamped by the server, never
 * taken from the sender.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class E2eeKeyPayload {
    private UUID channelId;
    private UUID fromUserId;
    private UUID toUserId;
    /** Sender's base64 X25519 public key (lets the recipient answer with its own key). */
    private String publicKey;
    /** Sender's SFrame participant id for this call. */
    private long participantId;
    /** Key generation, 0..255. */
    private int generation;
    /** Base64 AES-GCM nonce. */
    private String nonce;
    /** Base64 AES-GCM ciphertext of the media key. */
    private String sealedKey;
}
