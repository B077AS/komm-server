package com.kommserver.websocket.messages.payloads;

import com.kommserver.model.db.ServerMember;
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
public class ServerMemberPayload {

    private UUID serverId;
    private UUID userId;
    private LocalDateTime joinedAt;
    private ServerMember.Role role;
}
