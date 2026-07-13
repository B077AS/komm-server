package com.kommserver.model.db;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "server_bans", indexes = {
        @Index(name = "idx_server_ban_lookup", columnList = "server_id, user_id")
})
public class ServerBan {

    @Id
    @UuidGenerator
    @Column(name = "ban_id", updatable = false)
    private UUID banId;

    @Column(name = "server_id", nullable = false, length = 36)
    private UUID serverId;

    @Column(name = "user_id", nullable = false, length = 36)
    private UUID userId;

    @Column(name = "banned_by_user_id", length = 36)
    private UUID bannedByUserId;

    @Column(name = "reason")
    private String reason;

    @CreationTimestamp
    @Column(name = "banned_at", updatable = false)
    private LocalDateTime bannedAt;
}
