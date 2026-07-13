package com.kommserver.websocket.messages.payloads;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class ChannelDeletedPayload {
    private UUID channelId;
    private UUID serverId;
}
