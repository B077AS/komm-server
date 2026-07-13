package com.kommserver.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Sent to the affected user and to the hub when a member's base role changes. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberRoleUpdatedPayload {
    private UUID serverId;
    private UUID userId;
    private String newRole;
}
