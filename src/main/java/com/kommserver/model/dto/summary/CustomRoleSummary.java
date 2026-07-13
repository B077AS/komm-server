package com.kommserver.model.dto.summary;

import com.kommserver.model.db.ServerCustomRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomRoleSummary {
    private UUID roleId;
    private UUID serverId;
    private String roleName;
    private String color;
    private List<String> permissions;
    private int position;

    public static CustomRoleSummary from(ServerCustomRole entity) {
        return CustomRoleSummary.builder()
                .roleId(entity.getRoleId())
                .serverId(entity.getServerId())
                .roleName(entity.getRoleName())
                .color(entity.getColor())
                .permissions(entity.getPermissions() != null ? entity.getPermissions() : new ArrayList<>())
                .position(entity.getPosition())
                .build();
    }
}
