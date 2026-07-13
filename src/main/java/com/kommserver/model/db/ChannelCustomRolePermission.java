package com.kommserver.model.db;

import com.kommserver.model.converter.StringListConverter;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "channel_custom_role_permissions",
        indexes = @Index(name = "idx_ccrp_channel", columnList = "channel_id"))
@IdClass(ChannelCustomRolePermission.ChannelCustomRolePermissionId.class)
public class ChannelCustomRolePermission {

    @Id
    @Column(name = "channel_id", nullable = false, length = 36)
    private UUID channelId;

    @Id
    @Column(name = "custom_role_id", nullable = false, length = 36)
    private UUID customRoleId;

    @Convert(converter = StringListConverter.class)
    @Column(name = "allow_permissions", columnDefinition = "TEXT")
    @Builder.Default
    private List<String> allowPermissions = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "deny_permissions", columnDefinition = "TEXT")
    @Builder.Default
    private List<String> denyPermissions = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChannelCustomRolePermissionId implements Serializable {
        private UUID channelId;
        private UUID customRoleId;
    }
}
