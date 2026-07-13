package com.kommserver.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class VoiceConnectedUsersResponsePayload {
    private UUID serverId;
    private String correlationId;
    private int activeUsers;
    private int textChannelCount;
    private int voiceChannelCount;
    private Integer defaultChannelPanelWidth;
}