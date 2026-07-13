package com.kommserver.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Client → server: request to play a soundboard in the caller's current voice channel. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SoundboardPlayPayload {
    private UUID soundboardId;
}
