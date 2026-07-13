package com.kommserver.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Sent installation → hub when a server has been scheduled for deletion, so the hub can drop its
 * server/membership/invite rows and notify the affected app clients.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ServerDeletedPayload {
    private UUID serverId;
}
