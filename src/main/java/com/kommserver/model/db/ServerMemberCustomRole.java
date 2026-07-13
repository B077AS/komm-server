package com.kommserver.model.db;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "server_member_custom_roles",
        indexes = @Index(name = "idx_smcr_server_user", columnList = "server_id, user_id"))
@IdClass(ServerMemberCustomRole.ServerMemberCustomRoleId.class)
public class ServerMemberCustomRole {

    @Id
    @Column(name = "user_id", nullable = false, length = 36)
    private UUID userId;

    @Id
    @Column(name = "server_id", nullable = false, length = 36)
    private UUID serverId;

    @Id
    @Column(name = "role_id", nullable = false, length = 36)
    private UUID roleId;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServerMemberCustomRoleId implements Serializable {
        private UUID userId;
        private UUID serverId;
        private UUID roleId;
    }
}
