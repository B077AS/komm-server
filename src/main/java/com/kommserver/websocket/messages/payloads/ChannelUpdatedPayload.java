package com.kommserver.websocket.messages.payloads;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class ChannelUpdatedPayload {
    private UUID channelId;
    private UUID serverId;
    private String channelName;
    private String icon;
}
