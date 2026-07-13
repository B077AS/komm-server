package com.kommserver.model.db;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "message_attachments", indexes = {@Index(name = "idx_message_id", columnList = "message_id")})
public class MessageAttachment {

    @Id
    @UuidGenerator
    @Column(name = "attachment_id", nullable = false, updatable = false)
    private UUID attachmentId;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "file_type", nullable = false)
    private String fileType;
}