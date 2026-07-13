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
@Table(name = "channel_user_permissions",
        indexes = @Index(name = "idx_cup_channel", columnList = "channel_id"))
@IdClass(ChannelUserPermission.ChannelUserPermissionId.class)
public class ChannelUserPermission {

    @Id
    @Column(name = "channel_id", nullable = false, length = 36)
    private UUID channelId;

    @Id
    @Column(name = "user_id", nullable = false, length = 36)
    private UUID userId;

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
    public static class ChannelUserPermissionId implements Serializable {
        private UUID channelId;
        private UUID userId;
    }
}
