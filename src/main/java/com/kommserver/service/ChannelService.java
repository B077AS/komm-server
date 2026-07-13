package com.kommserver.service;

import com.kommserver.model.db.Channel;
import com.kommserver.model.dto.request.ChannelCreateRequest;
import com.kommserver.model.dto.request.ChannelUpdateRequest;
import com.kommserver.model.dto.summary.ChannelSummary;
import com.kommserver.repository.ChannelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelService {

    private final ChannelRepository channelRepository;
    private final MessageService messageService;

    public Map<UUID, ChannelSummary> getServerChannels(UUID serverId) {
        List<Channel> channels = channelRepository.findChannelsByServerId(serverId);

        return channels.stream()
                .map(this::toSummary)
                .collect(Collectors.toMap(ChannelSummary::getChannelId, dto -> dto));
    }

    public ChannelSummary createChannel(UUID serverId, ChannelCreateRequest request) {
        int position = channelRepository.maxPositionByServerId(serverId) + 1;

        Channel.ChannelType type = request.getChannelType() != null
                ? request.getChannelType()
                : Channel.ChannelType.TEXT;

        if (type == Channel.ChannelType.CLOCK
                && channelRepository.countByServerIdAndChannelType(serverId, "CLOCK") > 0) {
            throw new IllegalStateException("A clock channel already exists on this server");
        }

        String channelName;
        if (type == Channel.ChannelType.SPACER
                || type == Channel.ChannelType.DIVIDER
                || type == Channel.ChannelType.CLOCK) {
            channelName = UUID.randomUUID().toString();
        } else {
            channelName = request.getChannelName().trim();
        }

        Channel channel = Channel.builder()
                .serverId(serverId)
                .channelName(channelName)
                .channelType(type)
                .icon(request.getIcon())
                .position(position)
                .build();

        Channel saved = channelRepository.save(channel);
        log.info("Created channel id={} name={} type={} serverId={}",
                saved.getChannelId(), saved.getChannelName(), saved.getChannelType(), serverId);
        return toSummary(saved);
    }

    public Optional<ChannelSummary> getChannelById(UUID serverId, UUID channelId) {
        return channelRepository.findById(channelId)
                .filter(c -> c.getServerId().equals(serverId))
                .map(this::toSummary);
    }

    @Transactional
    public ChannelSummary updateChannel(UUID serverId, UUID channelId, ChannelUpdateRequest request) {
        Channel channel = channelRepository.findById(channelId)
                .filter(c -> c.getServerId().equals(serverId))
                .orElseThrow(() -> new IllegalArgumentException("Channel not found"));

        if (request.getChannelName() != null && !request.getChannelName().isBlank()) {
            if (channel.getChannelType() != Channel.ChannelType.SPACER
                    && channel.getChannelType() != Channel.ChannelType.DIVIDER) {
                channel.setChannelName(request.getChannelName().trim());
            }
        }
        channel.setIcon(request.getIcon());

        Channel saved = channelRepository.save(channel);
        log.info("Updated channel id={} name={} serverId={}", saved.getChannelId(), saved.getChannelName(), serverId);
        return toSummary(saved);
    }

    public void reorderChannels(UUID serverId, List<UUID> channelIds) {
        for (int i = 0; i < channelIds.size(); i++) {
            final int position = i;
            channelRepository.findById(channelIds.get(i))
                    .filter(c -> c.getServerId().equals(serverId))
                    .ifPresent(c -> {
                        c.setPosition(position);
                        channelRepository.save(c);
                    });
        }
        log.info("Reordered {} channels for serverId={}", channelIds.size(), serverId);
    }

    public void deleteChannel(UUID channelId) {
        messageService.deleteAllInChannel(channelId);
        channelRepository.deleteById(channelId);
        log.info("Deleted channel id={}", channelId);
    }

    private ChannelSummary toSummary(Channel channel) {
        return ChannelSummary.builder()
                .channelId(channel.getChannelId())
                .serverId(channel.getServerId())
                .channelName(channel.getChannelName())
                .channelType(ChannelSummary.ChannelType.valueOf(channel.getChannelType().name()))
                .description(channel.getDescription())
                .position(channel.getPosition() != null ? channel.getPosition() : 0)
                .icon(channel.getIcon())
                .build();
    }
}
