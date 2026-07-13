package com.kommserver.repository;

import com.kommserver.model.db.ServerMemberCustomRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ServerMemberCustomRoleRepository
        extends JpaRepository<ServerMemberCustomRole, ServerMemberCustomRole.ServerMemberCustomRoleId> {

    List<ServerMemberCustomRole> findByUserIdAndServerId(UUID userId, UUID serverId);

    void deleteByRoleId(UUID roleId);

    @Modifying
    @Query("DELETE FROM ServerMemberCustomRole r WHERE r.serverId = :serverId")
    void deleteByServerId(@Param("serverId") UUID serverId);
}
