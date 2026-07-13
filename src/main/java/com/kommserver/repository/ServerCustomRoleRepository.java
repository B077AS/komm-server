package com.kommserver.repository;

import com.kommserver.model.db.ServerCustomRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ServerCustomRoleRepository extends JpaRepository<ServerCustomRole, UUID> {

    List<ServerCustomRole> findByServerIdOrderByPosition(UUID serverId);

    List<ServerCustomRole> findByServerIdAndRoleIdIn(UUID serverId, Collection<UUID> roleIds);
}
