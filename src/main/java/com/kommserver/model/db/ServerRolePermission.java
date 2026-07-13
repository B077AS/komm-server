package com.kommserver.model.db;

import com.kommserver.model.converter.StringListConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "server_role_permissions",
        indexes = @Index(name = "idx_srp_server", columnList = "server_id"))
@IdClass(ServerRolePermission.ServerRolePermissionId.class)
public class ServerRolePermission {

    @Id
    @Column(name = "server_id", nullable = false, length = 36)
    private UUID serverId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private ServerMember.Role role;

    @Convert(converter = StringListConverter.class)
    @Column(name = "permissions", columnDefinition = "TEXT")
    private List<String> permissions;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServerRolePermissionId implements Serializable {
        private UUID serverId;
        private ServerMember.Role role;
    }
}
