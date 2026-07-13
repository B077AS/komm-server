package com.kommserver.websocket.messages.payloads;

import com.kommserver.model.dto.summary.SoundboardSummary;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Broadcast to all members of a server whenever its server-wide soundboard set
 * changes (add/replace/remove), carrying the full current list so open clients
 * can refresh.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SoundboardUpdatedPayload {
    private UUID serverId;
    private List<SoundboardSummary> soundboards;
}
