package com.kommserver.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Server → clients in a voice channel: stop the sounds a member had triggered. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SoundboardStoppedPayload {
    private UUID userId;
}
