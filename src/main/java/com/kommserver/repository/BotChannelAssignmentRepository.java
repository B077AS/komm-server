package com.kommserver.repository;

import com.kommserver.model.db.BotChannelAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BotChannelAssignmentRepository
        extends JpaRepository<BotChannelAssignment, BotChannelAssignment.BotChannelAssignmentId> {

    List<BotChannelAssignment> findByBotId(UUID botId);

    List<BotChannelAssignment> findByChannelId(UUID channelId);

    void deleteByBotId(UUID botId);

    void deleteByChannelId(UUID channelId);
}
