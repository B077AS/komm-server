package com.kommserver.model.db;

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

/**
 * An installation-local automated participant. Bots are never Hub user accounts — they live
 * entirely on the installation, and {@link Message#getSenderId()} may hold a bot's id instead of
 * a Hub user id when a bot posts a message. New bot types are added via {@link BotType} plus a
 * {@code BotBehavior} implementation; this entity only stores identity + freeform per-type config.
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "bots", indexes = {
        @Index(name = "idx_bot_server", columnList = "server_id")
})
public class Bot {

    @Id
    @UuidGenerator
    @Column(name = "bot_id", nullable = false, length = 36)
    private UUID botId;

    @Column(name = "server_id", nullable = false, length = 36)
    private UUID serverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "bot_type", nullable = false, length = 32)
    private BotType botType;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /** Type-specific settings, serialized as JSON (e.g. spawn chance, SFW-only flag). */
    @Column(name = "config", columnDefinition = "TEXT")
    private String config;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Timestamp createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Timestamp updatedAt;

    public enum BotType {
        ANIME_WAIFU
    }
}
