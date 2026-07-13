package com.kommserver.repository;

import com.kommserver.model.db.ServerMember;
import com.kommserver.model.db.ServerMember.ServerMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServerMemberRepository extends JpaRepository<ServerMember, ServerMemberId> {

    List<ServerMember> findByServerIdIn(List<UUID> serverIds);

    List<ServerMember> findByServerId(UUID serverId);

    boolean existsByServerIdAndUserId(UUID serverId, UUID userId);

    Optional<ServerMember> findByServerIdAndUserId(UUID serverId, UUID userId);

    @Modifying
    @Query("DELETE FROM ServerMember m WHERE m.serverId = :serverId")
    void deleteByServerId(@Param("serverId") UUID serverId);
}