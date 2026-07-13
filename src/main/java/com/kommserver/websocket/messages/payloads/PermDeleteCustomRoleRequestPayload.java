package com.kommserver.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermDeleteCustomRoleRequestPayload {
    private String correlationId;
    private UUID serverId;
    private UUID userId;
    private UUID roleId;
}
