package com.kommserver.repository;

import com.kommserver.model.db.ServerMember;
import com.kommserver.model.db.ServerRolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServerRolePermissionRepository
        extends JpaRepository<ServerRolePermission, ServerRolePermission.ServerRolePermissionId> {

    Optional<ServerRolePermission> findByServerIdAndRole(UUID serverId, ServerMember.Role role);

    List<ServerRolePermission> findByServerId(UUID serverId);

    List<ServerRolePermission> findByRole(ServerMember.Role role);

    @Modifying
    @Query("DELETE FROM ServerRolePermission p WHERE p.serverId = :serverId")
    void deleteByServerId(@Param("serverId") UUID serverId);
}
