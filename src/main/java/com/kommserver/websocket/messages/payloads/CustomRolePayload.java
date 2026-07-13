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
public class CustomRolePayload {
    private UUID roleId;
    private UUID serverId;
    private String roleName;
    private String color;
    private String baseRole;
    private List<String> permissions;
    private int position;
}
