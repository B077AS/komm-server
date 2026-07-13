package com.kommserver.repository;

import com.kommserver.model.db.ServerSoundboard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ServerSoundboardRepository extends JpaRepository<ServerSoundboard, UUID> {

    List<ServerSoundboard> findByServerIdOrderBySlotIndex(UUID serverId);

    @Modifying
    @Query("DELETE FROM ServerSoundboard s WHERE s.serverId = :serverId AND s.slotIndex = :slotIndex")
    void deleteByServerIdAndSlotIndex(@Param("serverId") UUID serverId,
                                      @Param("slotIndex") int slotIndex);
}
