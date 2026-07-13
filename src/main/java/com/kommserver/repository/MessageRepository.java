package com.kommserver.repository;

import com.kommserver.model.db.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    // Step 1: paginate without fetch join — just gets IDs/basic fields
    @Query("""
    SELECT m FROM Message m
    WHERE m.channelId = :channelId
      AND m.sentAt < :cursor
    ORDER BY m.sentAt DESC
    """)
    List<Message> findByChannelIdBeforeCursor(
            @Param("channelId") UUID channelId,
            @Param("cursor") LocalDateTime cursor,
            Pageable pageable);

    // Step 2: load reactions for a batch of messages
    @Query("""
    SELECT m FROM Message m
    LEFT JOIN FETCH m.reactions
    WHERE m.messageId IN :ids
    """)
    List<Message> findByIdsWithReactions(@Param("ids") List<UUID> ids);

    @Query("SELECT m.messageId FROM Message m WHERE m.channelId = :channelId")
    List<UUID> findMessageIdsByChannelId(@Param("channelId") UUID channelId);

    @Query(value = """
    SELECT * FROM (
        SELECT m.*, ROW_NUMBER() OVER (PARTITION BY m.channel_id ORDER BY m.sent_at DESC) AS rn
        FROM messages m
        WHERE m.channel_id IN (:channelIds)
    ) ranked WHERE rn = 1
    """, nativeQuery = true)
    List<Message> findLatestMessagePerChannel(@Param("channelIds") List<UUID> channelIds);

    @Modifying
    @Query("DELETE FROM Message m WHERE m.channelId = :channelId")
    void deleteByChannelId(@Param("channelId") UUID channelId);

    @Query("SELECT m.messageId FROM Message m WHERE m.serverId = :serverId")
    List<UUID> findMessageIdsByServerId(@Param("serverId") UUID serverId);

    @Modifying
    @Query("DELETE FROM Message m WHERE m.serverId = :serverId")
    void deleteByServerId(@Param("serverId") UUID serverId);
}