package com.kommserver.repository;

import com.kommserver.model.db.Channel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface ChannelRepository extends JpaRepository<Channel, UUID> {

    @Query(value = "SELECT * FROM channels WHERE server_id = ?1 ORDER BY position ASC", nativeQuery = true)
    List<Channel> findChannelsByServerId(UUID serverId);

    @Query(value = "SELECT COUNT(*) > 0 FROM channels WHERE channel_id = ?1 AND channel_type = 'TEXT'", nativeQuery = true)
    boolean isTextChannel(UUID channelId);

    long countByServerId(UUID serverId);

    @Query(value = "SELECT COALESCE(MAX(position), -1) FROM channels WHERE server_id = ?1", nativeQuery = true)
    int maxPositionByServerId(UUID serverId);

    @Query(value = "SELECT COUNT(*) FROM channels WHERE server_id = ?1 AND channel_type = ?2", nativeQuery = true)
    long countByServerIdAndChannelType(UUID serverId, String channelType);
}
