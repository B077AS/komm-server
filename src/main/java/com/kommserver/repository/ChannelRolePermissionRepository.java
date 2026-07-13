package com.kommserver.repository;

import com.kommserver.model.db.ChannelRolePermission;
import com.kommserver.model.db.ServerMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChannelRolePermissionRepository
        extends JpaRepository<ChannelRolePermission, ChannelRolePermission.ChannelRolePermissionId> {

    Optional<ChannelRolePermission> findByChannelIdAndRole(UUID channelId, ServerMember.Role role);

    List<ChannelRolePermission> findByChannelId(UUID channelId);

    void deleteByChannelId(UUID channelId);
}
