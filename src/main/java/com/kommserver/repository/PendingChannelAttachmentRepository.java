package com.kommserver.repository;

import com.kommserver.model.db.PendingChannelAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface PendingChannelAttachmentRepository extends JpaRepository<PendingChannelAttachment, UUID> {

    List<PendingChannelAttachment> findByUploadedAtBefore(LocalDateTime cutoff);
}
