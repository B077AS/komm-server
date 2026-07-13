package com.kommserver.model.db;

import com.kommserver.model.converter.StringListConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "server_custom_roles",
        indexes = @Index(name = "idx_scr_server", columnList = "server_id"))
public class ServerCustomRole {

    @Id
    @Column(name = "role_id", nullable = false, length = 36)
    private UUID roleId;

    @Column(name = "server_id", nullable = false, length = 36)
    private UUID serverId;

    @Column(name = "role_name", nullable = false, length = 50)
    private String roleName;

    @Column(name = "color", length = 7)
    private String color;

    @Enumerated(EnumType.STRING)
    @Column(name = "base_role")
    private ServerMember.Role baseRole;

    @Convert(converter = StringListConverter.class)
    @Column(name = "permissions", columnDefinition = "TEXT")
    @Builder.Default
    private List<String> permissions = new java.util.ArrayList<>();

    @Column(name = "position")
    @Builder.Default
    private int position = 0;
}
