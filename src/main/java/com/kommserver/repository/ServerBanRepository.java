package com.kommserver.repository;

import com.kommserver.model.db.ServerBan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ServerBanRepository extends JpaRepository<ServerBan, UUID> {
    boolean existsByServerIdAndUserId(UUID serverId, UUID userId);
    List<ServerBan> findAllByServerId(UUID serverId);
    void deleteByServerIdAndUserId(UUID serverId, UUID userId);

    @Modifying
    @Query("DELETE FROM ServerBan b WHERE b.serverId = :serverId")
    void deleteByServerId(@Param("serverId") UUID serverId);
}
