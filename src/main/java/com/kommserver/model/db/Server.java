package com.kommserver.model.db;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;


@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "servers")
public class Server {

    @Id
    @Column(name = "server_id", nullable = false, updatable = false, length = 36)
    private UUID serverId;

    @Column(name = "server_name", nullable = false, length = 100)
    private String serverName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "owner_id", nullable = false, length = 36)
    private UUID ownerId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "default_channel_panel_width")
    private Integer defaultChannelPanelWidth;

    /**
     * When true, the server has been scheduled for deletion. Members are already disconnected and the
     * hub-side server/membership rows are gone; the heavy installation-side data is purged in the
     * background by {@code ServerPurgeService}. Persisted so a restart resumes the purge.
     */
    @Column(name = "pending_deletion")
    @Builder.Default
    private Boolean pendingDeletion = false;

    @Column(name = "deletion_requested_at")
    private LocalDateTime deletionRequestedAt;

    @Column(name = "deletion_requested_by", length = 36)
    private UUID deletionRequestedBy;
}