package com.kommserver.repository;

import com.kommserver.model.db.MessageAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface MessageAttachmentRepository extends JpaRepository<MessageAttachment, UUID> {

    @Query("SELECT ma FROM MessageAttachment ma WHERE ma.messageId IN :messageIds")
    List<MessageAttachment> findByMessageIdIn(@Param("messageIds") List<UUID> messageIds);

    @Modifying
    @Query("DELETE FROM MessageAttachment ma WHERE ma.messageId IN :messageIds")
    void deleteByMessageIdIn(@Param("messageIds") Collection<UUID> messageIds);
}
