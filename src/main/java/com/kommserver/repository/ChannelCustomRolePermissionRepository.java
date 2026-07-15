package com.kommserver.repository;

import com.kommserver.model.db.ChannelCustomRolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChannelCustomRolePermissionRepository
        extends JpaRepository<ChannelCustomRolePermission, ChannelCustomRolePermission.ChannelCustomRolePermissionId> {

    List<ChannelCustomRolePermission> findByChannelId(UUID channelId);

    Optional<ChannelCustomRolePermission> findByChannelIdAndCustomRoleId(UUID channelId, UUID customRoleId);

    @Modifying
    @Query("DELETE FROM ChannelCustomRolePermission c WHERE c.channelId = :channelId")
    void deleteByChannelId(@Param("channelId") UUID channelId);

    @Modifying
    @Query("DELETE FROM ChannelCustomRolePermission c WHERE c.customRoleId = :customRoleId")
    void deleteByCustomRoleId(@Param("customRoleId") UUID customRoleId);
}
