package com.kommserver.model.db;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.sql.Timestamp;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "channels", indexes = {
        @Index(name = "idx_channel_type", columnList = "channel_type"),
        @Index(name = "idx_channel_server", columnList = "server_id")
})
public class Channel {

    @Id
    @UuidGenerator
    @Column(name = "channel_id", nullable = false, length = 36)
    private UUID channelId;

    @Column(name = "server_id", nullable = false, length = 36)
    private UUID serverId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", insertable = false, updatable = false)
    @JsonIgnore
    private Server server;

    @Column(name = "channel_name", nullable = false, length = 100)
    private String channelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false)
    private ChannelType channelType = ChannelType.TEXT;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "position")
    private Integer position = 0;

    @Column(name = "icon", length = 100)
    private String icon;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Timestamp createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Timestamp updatedAt;

    public enum ChannelType {
        TEXT,
        VOICE,
        SPACER,
        DIVIDER,
        TITLE,
        CLOCK
    }
}