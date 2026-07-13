package com.kommserver.repository;

import com.kommserver.model.db.MessageReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.UUID;

public interface MessageReactionRepository extends JpaRepository<MessageReaction, MessageReaction.MessageReactionId> {

    @Modifying
    @Query("DELETE FROM MessageReaction r WHERE r.id.messageId IN :messageIds")
    void deleteByMessageIdIn(@Param("messageIds") Collection<UUID> messageIds);
}