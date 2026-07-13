package com.kommserver.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermUpdateRoleRequestPayload {
    private String correlationId;
    private UUID serverId;
    private UUID userId;
    private String role;
    private List<String> permissions;
}
