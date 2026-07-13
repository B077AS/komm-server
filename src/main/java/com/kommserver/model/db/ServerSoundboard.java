package com.kommserver.model.db;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A server-scoped soundboard sound. Shared with everyone on the server.
 * Files live on disk under {@code {soundboards.base-path}/{serverId}/}.
 * Uploads are capped at 20 MB.
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "server_soundboards",
        uniqueConstraints = @UniqueConstraint(name = "uq_server_sb_slot",
                columnNames = {"server_id", "slot_index"}),
        indexes = @Index(name = "idx_server_sb_server", columnList = "server_id"))
public class ServerSoundboard {

    @Id
    @UuidGenerator
    @Column(name = "soundboard_id", nullable = false, updatable = false)
    private UUID soundboardId;

    @Column(name = "server_id", nullable = false)
    private UUID serverId;

    @Column(name = "slot_index", nullable = false)
    private int slotIndex;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_type", nullable = false)
    private String fileType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "uploader_id")
    private UUID uploaderId;

    @Column(name = "emoji", length = 64)
    private String emoji;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
