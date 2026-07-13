package com.kommserver.model.db;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "pending_channel_attachments")
public class PendingChannelAttachment {

    @Id
    @UuidGenerator
    @Column(name = "attachment_id", nullable = false, updatable = false)
    private UUID attachmentId;

    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    @Column(name = "uploader_id", nullable = false)
    private UUID uploaderId;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "file_type", nullable = false)
    private String fileType;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;
}
