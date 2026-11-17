package com.kommserver.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class VoiceTokenPayload {
    private String livekitUrl;
    private String token;
    /**
     * The channel this token was issued for. Lets the client discard tokens from
     * superseded joins when the user rapidly clicks between several voice channels.
     */
    private UUID channelId;
    /**
     * True when the call is end-to-end encrypted: every member encrypts its media with
     * SFrame and exchanges keys via E2EE_ANNOUNCE / E2EE_KEY. Older clients ignore it.
     */
    private boolean e2ee;
}