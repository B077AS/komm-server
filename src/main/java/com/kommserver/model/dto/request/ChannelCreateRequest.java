package com.kommserver.model.dto.request;

import com.kommserver.model.db.Channel.ChannelType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelCreateRequest {
    private String channelName;
    private ChannelType channelType;
    private String icon;
}
