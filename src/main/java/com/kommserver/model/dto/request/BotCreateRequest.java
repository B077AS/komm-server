package com.kommserver.model.dto.request;

import com.kommserver.model.db.Bot.BotType;
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
public class BotCreateRequest {
    private BotType botType;
    private String name;
    private String avatarUrl;
    private String config;
    private List<UUID> channelIds;
}
