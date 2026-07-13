package com.kommserver.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Server → clients in a voice channel: a member triggered a soundboard. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SoundboardPlayingPayload {
    private UUID soundboardId;
    private UUID userId;
}
