package com.kommserver.model.db;

import com.kommserver.model.converter.StringListConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "channel_role_permissions",
        indexes = @Index(name = "idx_crp_channel", columnList = "channel_id"))
@IdClass(ChannelRolePermission.ChannelRolePermissionId.class)
public class ChannelRolePermission {

    @Id
    @Column(name = "channel_id", nullable = false, length = 36)
    private UUID channelId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private ServerMember.Role role;

    /** Permissions explicitly granted on this channel, overriding server-level deny. */
    @Convert(converter = StringListConverter.class)
    @Column(name = "allow_permissions", columnDefinition = "TEXT")
    @Builder.Default
    private List<String> allowPermissions = new ArrayList<>();

    /** Permissions explicitly denied on this channel, overriding server-level allow. */
    @Convert(converter = StringListConverter.class)
    @Column(name = "deny_permissions", columnDefinition = "TEXT")
    @Builder.Default
    private List<String> denyPermissions = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChannelRolePermissionId implements Serializable {
        private UUID channelId;
        private ServerMember.Role role;
    }
}
