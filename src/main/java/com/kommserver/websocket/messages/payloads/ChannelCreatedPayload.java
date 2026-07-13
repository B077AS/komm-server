package com.kommserver.websocket.messages.payloads;

import com.kommserver.model.dto.summary.ChannelSummary;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelCreatedPayload {
    private UUID channelId;
    private UUID serverId;
    private String channelName;
    private ChannelSummary.ChannelType channelType;
    private String description;
    private int position;
    private String icon;
}
