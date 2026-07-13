package com.kommserver.model.db;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "server_members", indexes = {@Index(name = "idx_server_member", columnList = "server_id, user_id")})
@IdClass(ServerMember.ServerMemberId.class)
public class ServerMember {

    @Id
    @Column(name = "user_id", nullable = false, length = 36)
    private UUID userId;

    @Id
    @Column(name = "server_id", nullable = false, length = 36)
    private UUID serverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role = Role.MEMBER;

    @Column(name = "server_mic_enabled")
    private Boolean serverMicEnabled;

    @Column(name = "server_speaker_enabled")
    private Boolean serverSpeakerEnabled;

    @Column(name = "pokes_enabled")
    private Boolean pokesEnabled;

    @CreationTimestamp
    @Column(name = "joined_at", updatable = false)
    private LocalDateTime joinedAt;

    public boolean isServerMicEnabled() {
        return serverMicEnabled == null || serverMicEnabled;
    }

    public boolean isServerSpeakerEnabled() {
        return serverSpeakerEnabled == null || serverSpeakerEnabled;
    }

    public boolean isPokesEnabled() {
        return pokesEnabled == null || pokesEnabled;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServerMemberId implements Serializable {
        private UUID userId;
        private UUID serverId;
    }

    public enum Role {
        OWNER,
        ADMIN,
        MODERATOR,
        MEMBER;
    }
}
