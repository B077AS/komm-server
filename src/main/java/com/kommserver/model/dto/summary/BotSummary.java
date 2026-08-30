package com.kommserver.model.dto.summary;

import com.kommserver.model.db.Bot;
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
public class BotSummary {
    private UUID botId;
    private UUID serverId;
    private Bot.BotType botType;
    private String name;
    private String avatarUrl;
    private boolean enabled;
    private String config;
    private List<UUID> channelIds;
}
