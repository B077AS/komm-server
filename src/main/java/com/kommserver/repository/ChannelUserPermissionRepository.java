package com.kommserver.repository;

import com.kommserver.model.db.ChannelUserPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChannelUserPermissionRepository
        extends JpaRepository<ChannelUserPermission, ChannelUserPermission.ChannelUserPermissionId> {

    List<ChannelUserPermission> findByChannelId(UUID channelId);

    Optional<ChannelUserPermission> findByChannelIdAndUserId(UUID channelId, UUID userId);

    void deleteByChannelId(UUID channelId);
}
