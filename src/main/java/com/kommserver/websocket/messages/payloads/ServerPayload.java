package com.kommserver.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ServerPayload {

    private UUID serverId;
    private String serverName;
    private String description;
    private UUID ownerId;
    private LocalDateTime createdAt;
}
