package com.kommserver.service;

import com.kommserver.model.db.Bot;
import com.kommserver.model.db.BotChannelAssignment;
import com.kommserver.model.dto.request.BotCreateRequest;
import com.kommserver.model.dto.request.BotUpdateRequest;
import com.kommserver.model.dto.summary.BotSummary;
import com.kommserver.repository.BotChannelAssignmentRepository;
import com.kommserver.repository.BotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BotService {

    private final BotRepository botRepository;
    private final BotChannelAssignmentRepository botChannelAssignmentRepository;

    public List<BotSummary> getServerBots(UUID serverId) {
        return botRepository.findByServerId(serverId).stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    @Transactional
    public BotSummary createBot(UUID serverId, BotCreateRequest request) {
        Bot bot = Bot.builder()
                .serverId(serverId)
                .botType(request.getBotType())
                .name(request.getName().trim())
                .avatarUrl(request.getAvatarUrl())
                .config(request.getConfig())
                .enabled(true)
                .build();

        Bot saved = botRepository.save(bot);

        if (request.getChannelIds() != null && !request.getChannelIds().isEmpty()) {
            assignChannelsInternal(saved.getBotId(), saved.getBotType(), request.getChannelIds());
        }

        log.info("Created bot id={} type={} name={} serverId={}",
                saved.getBotId(), saved.getBotType(), saved.getName(), serverId);
        return toSummary(saved);
    }

    @Transactional
    public Optional<BotSummary> updateBot(UUID serverId, UUID botId, BotUpdateRequest request) {
        Optional<Bot> found = botRepository.findById(botId).filter(b -> b.getServerId().equals(serverId));
        if (found.isEmpty()) return Optional.empty();

        Bot bot = found.get();
        if (request.getName() != null && !request.getName().isBlank()) {
            bot.setName(request.getName().trim());
        }
        if (request.getAvatarUrl() != null) {
            bot.setAvatarUrl(request.getAvatarUrl());
        }
        if (request.getEnabled() != null) {
            bot.setEnabled(request.getEnabled());
        }
        if (request.getConfig() != null) {
            bot.setConfig(request.getConfig());
        }

        Bot saved = botRepository.save(bot);
        log.info("Updated bot id={} serverId={}", saved.getBotId(), serverId);
        return Optional.of(toSummary(saved));
    }

    @Transactional
    public Optional<BotSummary> assignChannels(UUID serverId, UUID botId, List<UUID> channelIds) {
        Optional<Bot> found = botRepository.findById(botId).filter(b -> b.getServerId().equals(serverId));
        if (found.isEmpty()) return Optional.empty();

        assignChannelsInternal(botId, found.get().getBotType(), channelIds);
        log.info("Reassigned bot id={} to {} channel(s), serverId={}", botId, channelIds.size(), serverId);
        return Optional.of(toSummary(found.get()));
    }

    /**
     * Replaces a bot's channel assignments, refusing any channel that already has a different bot
     * of the same {@link Bot.BotType} running in it — two waifu bots (say) spamming the same
     * channel isn't useful and just doubles the spawn rate unpredictably.
     */
    private void assignChannelsInternal(UUID botId, Bot.BotType botType, List<UUID> channelIds) {
        for (UUID channelId : channelIds) {
            List<UUID> otherBotIds = botChannelAssignmentRepository.findByChannelId(channelId).stream()
                    .map(BotChannelAssignment::getBotId)
                    .filter(id -> !id.equals(botId))
                    .toList();
            if (otherBotIds.isEmpty()) continue;

            boolean conflict = botRepository.findAllById(otherBotIds).stream()
                    .anyMatch(other -> other.getBotType() == botType);
            if (conflict) {
                throw new IllegalStateException(
                        "A " + botType + " bot is already assigned to one of the selected channels");
            }
        }

        botChannelAssignmentRepository.deleteByBotId(botId);
        List<BotChannelAssignment> assignments = channelIds.stream()
                .map(channelId -> BotChannelAssignment.builder().botId(botId).channelId(channelId).build())
                .collect(Collectors.toList());
        botChannelAssignmentRepository.saveAll(assignments);
    }

    @Transactional
    public boolean deleteBot(UUID serverId, UUID botId) {
        Optional<Bot> found = botRepository.findById(botId).filter(b -> b.getServerId().equals(serverId));
        if (found.isEmpty()) return false;

        botChannelAssignmentRepository.deleteByBotId(botId);
        botRepository.deleteById(botId);
        log.info("Deleted bot id={} serverId={}", botId, serverId);
        return true;
    }

    /** Bots assigned to a channel, enabled only — used by the bot engine on each incoming message. */
    public List<Bot> getEnabledBotsForChannel(UUID channelId) {
        List<UUID> botIds = botChannelAssignmentRepository.findByChannelId(channelId).stream()
                .map(BotChannelAssignment::getBotId)
                .collect(Collectors.toList());
        if (botIds.isEmpty()) return Collections.emptyList();
        return botRepository.findAllById(botIds).stream()
                .filter(Bot::getEnabled)
                .collect(Collectors.toList());
    }

    private BotSummary toSummary(Bot bot) {
        List<UUID> channelIds = botChannelAssignmentRepository.findByBotId(bot.getBotId()).stream()
                .map(BotChannelAssignment::getChannelId)
                .collect(Collectors.toList());

        return BotSummary.builder()
                .botId(bot.getBotId())
                .serverId(bot.getServerId())
                .botType(bot.getBotType())
                .name(bot.getName())
                .avatarUrl(bot.getAvatarUrl())
                .enabled(Boolean.TRUE.equals(bot.getEnabled()))
                .config(bot.getConfig())
                .channelIds(channelIds)
                .build();
    }
}
