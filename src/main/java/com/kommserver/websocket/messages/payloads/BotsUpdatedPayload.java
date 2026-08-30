package com.kommserver.websocket.messages.payloads;

import com.kommserver.model.dto.summary.BotSummary;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/** Broadcast whenever a server's bot roster changes (create/update/delete/reassign). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BotsUpdatedPayload {
    private UUID serverId;
    private List<BotSummary> bots;
}
